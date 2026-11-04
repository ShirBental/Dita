package app.dita.core.retrieval;

import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.QuestionNeeds.Type;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-054 (DEC-050) unit tests for question analysis and context selection, with NO embedding model: the keyword
 * channel is the in-process {@link LexicalRetriever}. Synthetic conversation (a choir concert; no fixture entities).
 * Semantic behaviour is covered only by the gated dita-semeval acceptance tests.
 */
class ConversationContextSelectorTest {
    static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    static final String[][] CONCERT = {
            {"a", "By the way, is the choir concert still happening this month?"},
            {"b", "Yes. What time on Friday?"},
            {"a", "Seven at Hillside Church, by the side door."},
            {"b", "Seven is early for us. Could we do eight instead?"},
            {"a", "Eight works. Let us make it Friday at eight, not seven."},
            {"b", "Good. I will bring the folders."},
            {"a", "If it snows, should we use the school hall?"},
            {"b", "The school hall is closed that week."},
            {"a", "Then snow means we cancel."},
            {"b", "Agreed. Friday at eight, side door, weather permitting."},
            {"a", "Unrelated, but my kettle broke. Any brand you like?"},
            {"b", "Mine is from Brightline. It is quiet."},
    };

    InMemoryMessageStore store = new InMemoryMessageStore();
    GroupScope g = new GroupScope("g1"), other = new GroupScope("g2");

    List<String> load(GroupScope scope, String prefix) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < CONCERT.length; i++) {
            String id = prefix + "m" + (i + 1);
            store.append(scope, new Message(id, scope.groupId(), CONCERT[i][0], T0.plusSeconds(60L * i), null, CONCERT[i][1], SourceKind.SYNTHETIC, false));
            ids.add(id);
        }
        return ids;
    }

    Function<List<Hit>, List<Hit>> live(GroupScope scope, Set<String> excluded) {
        return hits -> hits.stream().filter(h -> !excluded.contains(h.messageId()))
                .filter(h -> store.find(scope, h.messageId()).map(m -> !m.deleted()).orElse(false)).toList();
    }

    @Test void questionNeedsSplitsWhenWhereAndCondition() {
        var a = QuestionNeeds.of("When and where is the concert, and what happens if it snows?");
        assertEquals(List.of(Type.WHEN, Type.WHERE, Type.CONDITION), a.needs().stream().map(QuestionNeeds.Need::type).toList());
        assertEquals(Set.of("concert"), a.topic(), "frame words (and their base forms) are not topic words");
        assertTrue(a.needs().get(0).query().contains("concert"), "the elliptical 'When' clause carries the topic");
        String kq = QuestionNeeds.keywordQuery("When and where is the concert, and what happens if it snows?");
        for (String frame : List.of("when", "where", "what", "happens")) assertFalse(Arrays.asList(kq.split(" ")).contains(frame), kq);
        assertTrue(kq.contains("concert"), kq);
        assertEquals(Type.GENERAL, QuestionNeeds.of("Is the kettle quiet?").needs().get(0).type());
    }

    @Test void restatingADayIsNotANewPlace() {
        assertFalse(QuestionNeeds.values(Type.WHERE, "Thanks. Friday at eight, side door.").contains("Friday"));
    }

    @Test void ellipticalAnswersAndCorrectionsAreReconstructedAndLabelledInferred() {
        List<String> ids = load(g, "");
        var sel = new ConversationContextSelector().select(g, "When and where is the choir concert, and what happens if it snows?",
                new LexicalRetriever(store), store, live(g, Set.of()));
        List<String> got = sel.stream().map(Hit::messageId).toList();
        assertTrue(got.contains(ids.get(2)), "elliptical place answer (never says 'concert'): " + got);
        assertTrue(got.contains(ids.get(4)) || got.contains(ids.get(9)), "the corrected time: " + got);
        if (got.contains(ids.get(2))) assertTrue(got.contains(ids.get(4)), "a superseded proposal never travels without its correction: " + got);
        assertTrue(got.contains(ids.get(8)), "the condition's consequence: " + got);
        assertFalse(got.contains(ids.get(11)), "a message after a topic shift is not part of the exchange: " + got);
        assertTrue(sel.size() <= ConversationContextSelector.Settings.DEFAULTS.budget());
        List<String> sorted = new ArrayList<>(got); sorted.sort(Comparator.comparingInt(ids::indexOf));
        assertEquals(sorted, got, "conversation order");
        for (Hit h : sel) if (h.via().startsWith("context:")) assertTrue(h.inferredContext(), "reconstructed context is labelled inferred: " + h);
        assertTrue(sel.stream().anyMatch(h -> h.via().startsWith("context:")), "some messages were reached only by reconstruction");
    }

    @Test void ineligibleDeletedAndOtherGroupMessagesAreNeverSelected() {
        List<String> ids = load(g, "");
        List<String> otherIds = load(other, "x");
        store.tombstone(g, ids.get(4), T0.plusSeconds(60L * 4 + 30), DeletionMode.EARLY_PURGE, DeletionSource.PLATFORM_EVENT);
        Set<String> excluded = Set.of(ids.get(2));                 // e.g. before the requester's history access / session
        var sel = new ConversationContextSelector().select(g, "When and where is the choir concert?", new LexicalRetriever(store), store, live(g, excluded));
        List<String> got = sel.stream().map(Hit::messageId).toList();
        assertFalse(got.contains(ids.get(2)), "ineligible message is never added as context: " + got);
        assertFalse(got.contains(ids.get(4)), "deleted message is never added: " + got);
        assertTrue(Collections.disjoint(got, otherIds), "group isolation: " + got);
        assertFalse(got.isEmpty());
    }

    @Test void budgetIsAHardBound() {
        load(g, "");
        var tight = new ConversationContextSelector(new ConversationContextSelector.Settings(24, 3, 1, 20, 8, 5000));
        var sel = tight.select(g, "When and where is the choir concert, and what happens if it snows?", new LexicalRetriever(store), store, live(g, Set.of()));
        assertTrue(sel.size() <= 3, sel.toString());
        assertThrows(IllegalArgumentException.class, () -> new ConversationContextSelector.Settings(24, 0, 1, 20, 8, 5000));
    }

    @Test void nothingRetrievedMeansNothingSelected() {
        load(g, "");
        var sel = new ConversationContextSelector().select(g, "What is the wifi password?", new LexicalRetriever(store), store, live(g, Set.of()));
        assertTrue(sel.isEmpty(), "no relevant hit: the selector does not invent context (abstention stays possible): " + sel);
    }

    @Test void aKeptQuestionKeepsItsAnswerUnderBudgetPressure() {
        List<String> ids = load(g, "");
        for (int budget = 3; budget <= 12; budget++) {
            var sel = new ConversationContextSelector(new ConversationContextSelector.Settings(24, budget, 1, 20, 8, 5000))
                    .select(g, "When and where is the choir concert, and what happens if it snows?", new LexicalRetriever(store), store, live(g, Set.of()));
            List<String> got = sel.stream().map(Hit::messageId).toList();
            if (got.contains(ids.get(6))) assertTrue(got.contains(ids.get(7)), "budget " + budget + ": the backup question travels with its answer: " + got);
        }
    }
}
