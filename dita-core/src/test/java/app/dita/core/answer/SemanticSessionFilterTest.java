package app.dita.core.answer;

import app.dita.core.QueryService;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-054 G1 (Natasha): a SEMANTIC hit is filtered exactly like any other channel (session filter, deletion). Synthetic. */
class SemanticSessionFilterTest {
    @Test void aDenseHitOutsideTheSessionOrDeletedNeverReachesTheAnswer() {
        var s = new InMemoryMessageStore(); var g = new GroupScope("g1");
        Instant confirmed = Instant.parse("2026-11-03T10:00:00Z");
        s.append(g, new Message("g1m1", "g1", "u1", confirmed.minusSeconds(60), null, "Dr. Cedar is excellent for babies.", SourceKind.SYNTHETIC, false));
        s.append(g, new Message("g1m2", "g1", "u1", confirmed.plusSeconds(60), null, "Dr. Cedar is excellent for babies, truly.", SourceKind.SYNTHETIC, false));
        s.append(g, new Message("g1m3", "g1", "u1", confirmed.plusSeconds(61), null, "Dr. Cedar is excellent for newborns.", SourceKind.SYNTHETIC, true));
        Retriever dense = (sc, q, k) -> List.of(new Hit("g1m1", 0.9, "vespa:dense", false), new Hit("g1m2", 0.8, "vespa:dense", false),
                new Hit("g1m3", 0.85, "vespa:dense", false));
        var q = new QueryService(s, dense, new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (ss, m) -> Optional.empty(), s), 8, 200);
        var hits = q.messagesOnly(m -> !m.sentAt().isBefore(confirmed)).relevantMessages(g, "u1", "Which pediatrician do parents trust?", 8);
        assertEquals(List.of("g1m2"), hits.stream().map(Hit::messageId).toList(), "pre-session and deleted dense hits are dropped");
    }
}
