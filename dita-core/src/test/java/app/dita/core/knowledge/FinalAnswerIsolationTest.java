package app.dita.core.knowledge;

import app.dita.core.answer.GroundedAnswer;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.Message;
import app.dita.core.retrieval.Hit;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dita.core.knowledge.Task047CodexReviewTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The round-2 interleaving: a writer starts after the final verifier reads the correction,
 * before the evidence-view read. Either observe that commit and retry, or serialize it after the gate. */
class FinalAnswerIsolationTest {
    @Test void editBetweenFinalGateReadsCannotInvalidateReturnedEvidence() throws Exception { race("edit"); }
    @Test void deletionBetweenFinalGateReadsCannotInvalidateReturnedEvidence() throws Exception { race("delete"); }
    @Test void unrelatedWithdrawalDoesNotSuppressValidEvidence() throws Exception { race("unrelated"); }
    @Test void aPreGateChangeIsRecomposedFromItsNewRevision() { retries(1); }
    @Test void aSecondInvalidationFailsClosedWithoutUnboundedRetries() { retries(2); }

    private void retries(int budget) {
        var truth = dinner(false);
        var mutations = new AtomicInteger(); var attempts = new AtomicInteger();
        MessageStore view = (MessageStore) Proxy.newProxyInstance(MessageStore.class.getClassLoader(), new Class<?>[]{MessageStore.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(truth, args);
                        boolean verifier = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f ->
                                f.getClassName().equals("app.dita.core.answer.AnswerVerifier") && f.getMethodName().equals("valid"));
                        boolean gate = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f -> f.getMethodName().equals("currentAtReturn"));
                        if (method.getName().equals("find") && args[1].equals("b") && verifier && !gate && mutations.get() < budget) {
                            int revision = mutations.incrementAndGet();
                            changes(truth).edit(G, "b", "Dinner is at " + (8 + revision) + " PM.", T.plusSeconds(180 + revision));
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
        GroundedAnswer answer = query(view, (g, q, k) -> {
            attempts.incrementAndGet(); return List.of(new Hit("b", 1, "direct", false));
        }, true).ask(G, "viewer", "What time is dinner?");
        assertEquals(2, attempts.get(), "one bounded retry");
        if (budget == 1) assertTrue(answer.found() && answer.text().contains("9 PM") && !answer.text().contains("8 PM"), answer.text());
        else assertFalse(answer.found(), answer.text());
    }

    private void race(String operation) throws Exception {
        var truth = dinner(false);
        truth.append(G, m("x", 90, null, "Parking is free."));
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var intercepted = new AtomicBoolean();
        MessageStore view = (MessageStore) Proxy.newProxyInstance(MessageStore.class.getClassLoader(),
                new Class<?>[]{MessageStore.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(truth, args);
                        boolean gate = Arrays.stream(Thread.currentThread().getStackTrace())
                                .anyMatch(f -> f.getMethodName().equals("currentAtReturn"));
                        boolean verifier = Arrays.stream(Thread.currentThread().getStackTrace())
                                .anyMatch(f -> f.getClassName().equals("app.dita.core.answer.AnswerVerifier") && f.getMethodName().equals("valid"));
                        if (method.getName().equals("find") && args[1].equals("b") && gate && verifier
                                && intercepted.compareAndSet(false, true)) {
                            read.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("gate not released");
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var answering = threads.submit(() -> query(view, (g, q, k) -> List.of(new Hit("b", 1, "direct", false)), true)
                    .ask(G, "viewer", "What time is dinner?"));
            assertTrue(read.await(5, TimeUnit.SECONDS), "pause inside the real final verifier");
            var writing = threads.submit(() -> {
                writerStarted.countDown();
                if (operation.equals("edit")) changes(truth).edit(G, "b", "Dinner plans remain open.", T.plusSeconds(180));
                else changes(truth).delete(G, operation.equals("delete") ? "b" : "x", T.plusSeconds(180), DeletionSource.FORGET_COMMAND);
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            boolean committedDuringGate;
            try { writing.get(250, TimeUnit.MILLISECONDS); committedDuringGate = true; }
            catch (TimeoutException expectedContention) { committedDuringGate = false; }
            release.countDown();
            GroundedAnswer answer = answering.get(5, TimeUnit.SECONDS);
            writing.get(5, TimeUnit.SECONDS);
            if (committedDuringGate && !operation.equals("unrelated")) {
                assertFalse(answer.text().contains("8 PM"), "writer committed inside gate, but stale answer escaped: " + answer.text());
            } else {
                assertTrue(answer.found() && answer.text().contains("8 PM"), "a valid serialized answer must survive: " + answer.text());
            }
            GroundedAnswer next = ask(truth, true, "What time is dinner?");
            if (operation.equals("unrelated")) assertTrue(next.text().contains("8 PM"));
            else assertFalse(next.text().contains("7 PM") || next.text().contains("8 PM"), next.text());
            for (var citation : next.citations()) {
                Message source = truth.find(G, citation.messageId()).orElseThrow();
                assertFalse(source.deleted());
                assertTrue(source.text().contains(citation.quote()));
            }
        } finally { release.countDown(); threads.shutdownNow(); }
    }
}
