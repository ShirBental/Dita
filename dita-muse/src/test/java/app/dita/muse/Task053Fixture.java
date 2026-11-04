package app.dita.muse;

import app.dita.core.QueryService;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.events.EventPublisher;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.knowledge.InMemoryExtractionQueue;
import app.dita.core.knowledge.KnowledgeProposer;
import app.dita.core.knowledge.Proposal;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TASK-053 synthetic fixture: two groups ingested into one in-memory store and extracted through Dita's REAL extraction
 * service (EvidenceValidator, RelationRules, ValidityReconciler). The proposer replays the fixture's proposals (standing
 * in for Muse extraction output); everything that becomes knowledge is decided by Dita. No network.
 */
final class Task053Fixture {
    static final ObjectMapper JSON = new ObjectMapper();
    static final GroupScope PICNIC = new GroupScope("task053-picnic");
    static final GroupScope OTHER = new GroupScope("task053-other");
    static final Instant T0 = Instant.parse("2027-06-01T09:00:00Z");

    final InMemoryMessageStore store = new InMemoryMessageStore();
    final Map<String, ExtractionService.Report> reports = new HashMap<>();

    static JsonNode resource(String name) {
        try (InputStream in = Task053Fixture.class.getResourceAsStream("/task053/" + name)) {
            return JSON.readTree(in);
        } catch (Exception e) { throw new IllegalStateException("fixture " + name, e); }
    }

    Task053Fixture() {
        JsonNode groups = resource("conversation.json").path("groups");
        for (GroupScope g : List.of(PICNIC, OTHER)) load(g, groups.path(g.groupId()));
    }

    private void load(GroupScope g, JsonNode rows) {
        Map<String, List<Proposal.Item>> byMessage = new HashMap<>();
        List<Message> messages = new ArrayList<>();
        for (JsonNode r : rows) {
            Message m = new Message(r.path("id").asText(), g.groupId(), r.path("author").asText(),
                    T0.plusSeconds(60L * r.path("minute").asInt()), null, r.path("text").asText(), SourceKind.SYNTHETIC, false);
            store.append(g, m);
            messages.add(m);
            List<Proposal.Item> items = new ArrayList<>();
            for (JsonNode p : r.path("proposal")) {
                List<Proposal.Qualifier> qs = new ArrayList<>();
                p.path("qualifiers").forEach(q -> qs.add(new Proposal.Qualifier(q.path("kind").asText(), m.id(), q.path("quote").asText())));
                items.add(new Proposal.Item(p.path("status").asText(), p.path("certainty").asText(), p.path("subject_id").asText(),
                        p.path("subject").asText(), p.path("predicate").asText(), null, null,
                        List.of(new Proposal.Quote(m.id(), p.path("quote").asText())), qs, List.of()));
            }
            byMessage.put(m.id(), items);
        }
        KnowledgeProposer proposer = (scope, batchId, targets, context) -> {
            List<Proposal.Item> out = new ArrayList<>();
            for (Message t : targets) out.addAll(byMessage.getOrDefault(t.id(), List.of()));
            return new Proposal(out, "task053-fixture-v1");
        };
        // one batch per message, in conversation order, so later decisions are reconciled against earlier ones
        Clock clock = Clock.fixed(T0.plusSeconds(3 * 3600), ZoneOffset.UTC);
        var queue = new InMemoryExtractionQueue();
        var service = new ExtractionService(store, queue, proposer, EventPublisher.NONE, clock, 1);
        for (Message m : messages) {
            if (byMessage.get(m.id()).isEmpty()) continue;
            if (!service.enqueue(g, m)) throw new IllegalStateException("not enqueued: " + m.id());
            for (var b : service.collect(g)) {
                service.approve(g, b.id(), "task053-fixture");
                Optional<ExtractionService.Report> rep = service.process(g, b.id());
                rep.ifPresent(x -> reports.put(m.id(), x));
            }
        }
    }

    QueryService deterministic() {
        return new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS),
                new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, m) -> Optional.empty(), store), 8, 200, id -> id, null);
    }
}
