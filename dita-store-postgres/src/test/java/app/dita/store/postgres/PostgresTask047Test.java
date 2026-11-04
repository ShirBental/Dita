package app.dita.store.postgres;

import app.dita.core.QueryService;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.knowledge.InMemoryExtractionQueue;
import app.dita.core.knowledge.Proposal;
import app.dita.core.knowledge.ValidityReconciler;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.retrieval.Retriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-047 Codex review on PostgreSQL (Codex's Pg047/PgParent047 reproductions, same texts and sequences). Runs on V5
 * (UNRESOLVED stored as SUPERSEDED) and on V6. Skipped unless DITA_TEST_DB_URL points at a throwaway, migrated database.
 */
class PostgresTask047Test {
    static final Instant T = Instant.parse("2026-12-01T12:00:00Z");
    static DataSource ds;
    static JdbcTemplate jdbc;
    PostgresMessageStore s;

    @BeforeEach void setUp() {
        TestDb.assumeConfigured();
        if (ds == null) { ds = TestDb.dataSource(); jdbc = new JdbcTemplate(ds); }
        s = new PostgresMessageStore(ds);
        TestDb.assumeV5(s);
    }

    Assertion.Validity withdrawnState() { return s.hasV6() ? Assertion.Validity.UNRESOLVED : Assertion.Validity.SUPERSEDED; }

    static Proposal.Item p(String id, String sid, String sub, String quote) {
        return new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", sid, sub, "states", null, null,
                List.of(new Proposal.Quote(id, quote)), List.of(), List.of());
    }

    void extract(GroupScope g, List<Proposal.Item> items, String... ids) {
        var x = new ExtractionService(s, new InMemoryExtractionQueue(), (a, b, c, d) -> new Proposal(items, "pg047"),
                new PostgresEventPublisher(ds), Clock.fixed(T.plusSeconds(5000), ZoneOffset.UTC), 50);
        for (String id : ids) x.enqueue(g, s.find(g, id).orElseThrow());
        var b = x.collect(g).getFirst();
        x.approve(g, b.id(), "local-synthetic");
        assertEquals(ExtractionBatch.Status.DONE, x.process(g, b.id()).orElseThrow().status());
    }

    GroundedAnswer ask(GroupScope g, Retriever r, boolean limited, String q) {
        var access = new HistoryAccessFilter(limited ? HistoryAccess.SINCE_REQUESTER_JOINED : HistoryAccess.FULL_GROUP_HISTORY,
                (a, b) -> Optional.of(T.minusSeconds(1)), s);
        return new QueryService(s, r, access, 10, 300, id -> "Ana").ask(g, "viewer", q);
    }

    DeletionService changes() {
        return new DeletionService(s, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, new PostgresEventPublisher(ds), 50);
    }

    GroupScope dinner(boolean mixed) {
        GroupScope g = TestDb.newGroup(jdbc, "pg047");
        s.append(g, new Message("a", g.groupId(), "ana", T, null, "Dinner is at 7 PM." + (mixed ? " Parking is free." : ""), SourceKind.SYNTHETIC, false));
        s.append(g, new Message("b", g.groupId(), "ana", T.plusSeconds(60), null, "Correction: dinner is at 8 PM.", SourceKind.SYNTHETIC, false));
        var items = new ArrayList<Proposal.Item>();
        items.add(p("a", "dinner", "Dinner", "Dinner is at 7 PM."));
        items.add(p("b", "dinner", "dinner", "dinner is at 8 PM."));
        if (mixed) items.add(p("a", "parking", "Parking", "Parking is free."));
        extract(g, items, "a", "b");
        return g;
    }

    KnowledgeItem item(GroupScope g, String mid, String sid) {
        return s.knowledge(g).stream().filter(k -> k.sourceMessageIds().contains(mid) && k.assertion().subjectId().equals(sid)).findFirst().orElseThrow();
    }

    @Test void f1_mixedMessageNeverLeaksTheWithdrawnClaim_andTheActiveSpanIsStillAnswered() {
        for (String mode : List.of("early", "late")) {
            GroundedAnswer[] out = new GroundedAnswer[3];
            GroupScope g = dinner(true);
            changes().delete(g, "b", T.plusSeconds(mode.equals("early") ? 120 : 1200), DeletionSource.FORGET_COMMAND);
            assertEquals(withdrawnState(), item(g, "a", "dinner").assertion().validity());
            out[0] = ask(g, new LexicalRetriever(s), false, "What time is dinner?");
            out[1] = ask(g, new LexicalRetriever(s), true, "What time is dinner?");
            out[2] = ask(g, new LexicalRetriever(s), true, "Is parking free?");
            for (int i = 0; i < 2; i++) assertFalse(out[i].text().contains("7 PM") || out[i].text().contains("8 PM"), mode + " → " + out[i].text());
            assertTrue(out[2].text().contains("Parking is free") && !out[2].text().contains("7 PM"), mode + " → " + out[2].text());
        }
    }

    @Test void f2_withdrawalDuringRetrievalIsCaughtByTheFinalGate() {
        GroupScope g = dinner(false);
        Retriever withdrawing = (a, b, k) -> {
            changes().delete(g, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
            return List.of(new Hit("a", 1, "direct", false));
        };
        GroundedAnswer a = ask(g, withdrawing, true, "What time is dinner?");
        assertEquals(withdrawnState(), item(g, "a", "dinner").assertion().validity());
        assertFalse(a.text().contains("7 PM"), a.text());                      // Codex PG actual: "Dinner is at 7 PM." [msg a]
    }

    @Test void f3_aRejectedRelationRestoresTheClaimOnV6_andStaysFailClosedOnV5() {
        GroupScope g = TestDb.newGroup(jdbc, "pg047-picnic");
        s.append(g, new Message("a", g.groupId(), "ana", T, null, "Let's put picnic signs at the entrance.", SourceKind.SYNTHETIC, false));
        s.append(g, new Message("b", g.groupId(), "ana", T.plusSeconds(60), null, "Decision: the picnic starts at noon.", SourceKind.SYNTHETIC, false));
        var later = new Proposal.Item("DECISION", "ASSERTED", "picnic", "picnic", "decides", null, null,
                List.of(new Proposal.Quote("b", "Decision: the picnic starts at noon.")), List.of(), List.of());
        extract(g, List.of(p("a", "picnic", "picnic", "Let's put picnic signs at the entrance."), later), "a", "b");
        assertEquals(Assertion.Validity.SUPERSEDED, item(g, "a", "picnic").assertion().validity());
        s.atomically(g, () -> ValidityReconciler.rejectRelations(s, g,
                s.relations(g).stream().map(KnowledgeRelation::key).toList()));
        assertEquals(s.hasV6() ? Assertion.Validity.ACTIVE : Assertion.Validity.SUPERSEDED, item(g, "a", "picnic").assertion().validity());
        if (s.hasV6()) assertTrue(ask(g, new LexicalRetriever(s), false, "Where should we put picnic signs?").text().contains("entrance"));
    }

    @Test void f5_editingAnUnrelatedParentKeepsASelfContainedCorrection() {
        GroupScope g = TestDb.newGroup(jdbc, "pg047-parent");
        s.append(g, new Message("a", g.groupId(), "ana", T, null, "Dinner is at 7 PM.", SourceKind.SYNTHETIC, false));
        s.append(g, new Message("parent", g.groupId(), "ben", T.plusSeconds(30), null, "Please remind us about the plans.", SourceKind.SYNTHETIC, false));
        s.append(g, new Message("b", g.groupId(), "ana", T.plusSeconds(60), "parent", "Correction: dinner is at 8 PM.", SourceKind.SYNTHETIC, false));
        extract(g, List.of(p("a", "dinner", "Dinner", "Dinner is at 7 PM."), p("b", "dinner", "dinner", "dinner is at 8 PM.")), "a", "b");
        changes().edit(g, "parent", "Please remind us about parking.", T.plusSeconds(120));
        assertEquals(Assertion.Validity.SUPERSEDED, item(g, "a", "dinner").assertion().validity());
        assertEquals(1, s.relations(g).size());
        GroundedAnswer a = ask(g, new LexicalRetriever(s), false, "What time is dinner?");
        assertTrue(a.text().contains("8 PM"), a.text());
    }
}
