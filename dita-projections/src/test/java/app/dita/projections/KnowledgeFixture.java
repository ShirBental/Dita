package app.dita.projections;

import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.model.SourceKind;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;

import java.time.Instant;
import java.util.List;

/**
 * Synthetic, extracted-style knowledge (modelled on parents-c c02/c04/c17): RFC-002 assertions whose spans are computed
 * by Dita from the message text (indexOf), never copied from a model. Each statement carries GLOSS, a canary for "model
 * wording", which must never reach an index or the graph.
 */
final class KnowledgeFixture {
    static final String GLOSS = "glosscanary";
    static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    static final String C02 = "Dr. Levi on Herzl St. is excellent, we've been going for years";
    static final String C04 = "Dr. Levi is fine for babies but avoid him for teenagers, he was dismissive with my son";
    static final String C17 = "my neighbour says Dr. Katz is the best dentist for cleaning";

    final MessageStore store = new InMemoryMessageStore();
    final GroupScope g;

    KnowledgeFixture(String groupId) {
        g = new GroupScope(groupId);
        store.append(g, msg("c02", "yossi", 2, C02));
        store.append(g, msg("c04", "ron", 4, C04));
        store.append(g, msg("c17", "eve", 17, C17));
    }

    Message msg(String id, String author, int minute, String text) {
        return new Message(id, g.groupId(), author, T0.plusSeconds(60L * minute), null, text, SourceKind.SYNTHETIC, false);
    }

    static KnowledgeItem.Evidence quote(String mid, String text, String q, KnowledgeItem.Role role) {
        int s = text.indexOf(q);
        if (s < 0) throw new IllegalArgumentException("fixture quote not in message");
        return KnowledgeItem.Evidence.span(mid, role, s, s + q.length());
    }

    static Assertion.Qualifier qualifier(Assertion.Qualifier.Kind kind, String mid, String text, String q) {
        int s = text.indexOf(q);
        return new Assertion.Qualifier(kind, mid, s, s + q.length());
    }

    KnowledgeItem c02() {
        Assertion a = new Assertion(Assertion.Status.ATTRIBUTED_STATEMENT, Assertion.Certainty.ASSERTED, "yossi", null,
                "ent-levi", "Dr. Levi", "recommends", List.of(), List.of(new Assertion.Entity("ent-herzl", "Herzl St.")),
                T0.plusSeconds(120), Assertion.Validity.ACTIVE, "muse-spark-1.3/extract-v2", "xb-fixture");
        return new KnowledgeItem("k-c02", g.groupId(), KnowledgeItem.Kind.RECOMMENDATION, "pediatrician",
                "Yossi strongly endorses the doctor " + GLOSS, 1, MessageLink.Method.LLM,
                List.of(quote("c02", C02, "Dr. Levi on Herzl St. is excellent", KnowledgeItem.Role.ASSERTS)), false, a);
    }

    KnowledgeItem c04() {
        Assertion a = new Assertion(Assertion.Status.ATTRIBUTED_STATEMENT, Assertion.Certainty.ASSERTED, "ron", null,
                "ent-levi", "Dr. Levi", "advises_against",
                List.of(qualifier(Assertion.Qualifier.Kind.POPULATION, "c04", C04, "for teenagers")), List.of(),
                T0.plusSeconds(240), Assertion.Validity.ACTIVE, "muse-spark-1.3/extract-v2", "xb-fixture");
        return new KnowledgeItem("k-c04", g.groupId(), KnowledgeItem.Kind.RECOMMENDATION, "pediatrician",
                "Ron warns about the doctor " + GLOSS, -1, MessageLink.Method.LLM,
                List.of(quote("c04", C04, "avoid him for teenagers", KnowledgeItem.Role.ASSERTS)), false, a);
    }

    KnowledgeItem c17() {
        Assertion a = new Assertion(Assertion.Status.REPORTED_CLAIM, Assertion.Certainty.HEARSAY, "eve", "my neighbour",
                "ent-katz", "Dr. Katz", "states",
                List.of(qualifier(Assertion.Qualifier.Kind.CONDITION, "c17", C17, "for cleaning")), List.of(),
                T0.plusSeconds(17 * 60), Assertion.Validity.ACTIVE, "muse-spark-1.3/extract-v2", "xb-fixture");
        return new KnowledgeItem("k-c17", g.groupId(), KnowledgeItem.Kind.CLAIM, "dental care",
                "Eve heard Katz is top " + GLOSS, 1, MessageLink.Method.LLM,
                List.of(quote("c17", C17, "Dr. Katz is the best dentist", KnowledgeItem.Role.ASSERTS)), false, a);
    }

    /** Adds the three items and c04 QUALIFIES c02 (evidence c04). */
    KnowledgeFixture withKnowledge() {
        store.addKnowledge(g, c02());
        store.addKnowledge(g, c04());
        store.addKnowledge(g, c17());
        store.addRelation(g, new KnowledgeRelation(g.groupId(), "k-c04", "k-c02", KnowledgeRelation.Kind.QUALIFIES, "c04"));
        return this;
    }

    KnowledgeTruth truth() { return KnowledgeTruth.of(store); }
}
