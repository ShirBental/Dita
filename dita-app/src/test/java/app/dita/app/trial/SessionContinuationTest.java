package app.dita.app.trial;

import app.dita.app.trial.SyntheticTrialController.AnswerMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-054 (DEC-050; Natasha M1): the explicit continuation option names ONE recorded session and fails closed. */
class SessionContinuationTest {
    static final Instant NOW = Instant.parse("2026-11-04T16:00:00Z");
    static final String C = SyntheticTrialController.CONFIRM_IN_TELEGRAM;
    static final String S1 = "2026-11-04T01:10:06.773417Z", S2 = "2026-11-04T13:15:54.329377Z";

    /** A fresh directory under target/ (the sandbox refuses the default temp directory). */
    static Path fresh() throws Exception {
        return Files.createDirectories(Path.of("target", "session-continuation-test", java.util.UUID.randomUUID().toString()));
    }

    static Path runs(Path dir, String... confirmedAt) throws Exception {
        int i = 0;
        for (String c : confirmedAt) {
            Path d = Files.createDirectories(dir.resolve("run-" + (i++)));
            Files.writeString(d.resolve("session.json"), "{\"kind\":\"session\",\"confirmed_at\":\"" + c + "\"}");
        }
        Files.createDirectories(dir.resolve("run-new"));                     // the starting run: no session.json yet
        return dir;
    }

    @Test void emptyMeansThisSessionOnly() throws Exception {
        Path d = fresh();
        assertNull(SyntheticTrialController.continuation("", AnswerMode.MESSAGES, C, NOW, runs(d, S1, S2)));
        assertNull(SyntheticTrialController.continuation(null, AnswerMode.MESSAGES, C, NOW, d));
    }

    @Test void acceptsTheLatestRecordedSession() throws Exception {
        Path d = fresh();
        assertEquals(Instant.parse(S2), SyntheticTrialController.continuation(S2, AnswerMode.MESSAGES, C, NOW, runs(d, S1, S2)));
    }

    @Test void refusesAnEarlierSessionWhenALaterOneExists() throws Exception {
        Path d = fresh();
        Path r = runs(d, S1, S2);
        var e = assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation(S1, AnswerMode.MESSAGES, C, NOW, r));
        assertTrue(e.getMessage().contains("later session"), e.getMessage());
    }

    @Test void explicitEndAllowsOnlyOneOlderRecordedSession() throws Exception {
        Path r = runs(fresh(), S1, S2);
        assertEquals(Instant.parse(S1), SyntheticTrialController.continuation(S1, S2, AnswerMode.MESSAGES, C, NOW, r));
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation(S1, "2026-11-04T14:00:00Z", AnswerMode.MESSAGES, C, NOW, r));
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation("", S2, AnswerMode.MESSAGES, C, NOW, r));
    }

    @Test void carryOverFiltersAuthorsAndBothWindowBoundaries() {
        Instant start=Instant.parse(S1), end=Instant.parse(S2);
        java.util.function.BiFunction<Instant,String,app.dita.core.model.Message> msg=(at,author) ->
            new app.dita.core.model.Message("m1","g",author,at,null,"fictional",app.dita.core.model.SourceKind.SYNTHETIC,false);
        var authors=java.util.Set.of("alice","bob");
        assertTrue(SyntheticTrialController.inContinuation(msg.apply(start.plusSeconds(1),"alice"),start,end,authors));
        assertFalse(SyntheticTrialController.inContinuation(msg.apply(start.minusSeconds(1),"alice"),start,end,authors));
        assertFalse(SyntheticTrialController.inContinuation(msg.apply(end,"alice"),start,end,authors));
        assertFalse(SyntheticTrialController.inContinuation(msg.apply(end.plusSeconds(1),"bob"),start,end,authors));
        assertFalse(SyntheticTrialController.inContinuation(msg.apply(start.plusSeconds(1),"other"),start,end,authors));
    }

    @Test void refusesAnInstantThatIsNotARecordedSession() throws Exception {
        Path d = fresh();
        Path r = runs(d, S1, S2);
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation("1970-01-01T00:00:00Z", AnswerMode.MESSAGES, C, NOW, r),
                "an arbitrary early instant would pull in the whole group history");
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation("2026-11-04T13:15:54Z", AnswerMode.MESSAGES, C, NOW, r),
                "must match exactly");
    }

    @Test void refusesWrongModeMalformedOrFuture() throws Exception {
        Path d = fresh();
        Path r = runs(d, S2);
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation(S2, AnswerMode.KNOWLEDGE, C, NOW, r));
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation(S2, AnswerMode.MESSAGES, "", NOW, r));
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation("yesterday", AnswerMode.MESSAGES, C, NOW, r));
        assertThrows(IllegalArgumentException.class, () -> SyntheticTrialController.continuation(S2, AnswerMode.MESSAGES, C, Instant.parse("2026-11-04T10:00:00Z"), r));
    }
}
