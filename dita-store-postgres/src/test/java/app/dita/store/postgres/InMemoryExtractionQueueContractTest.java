package app.dita.store.postgres;

import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.knowledge.InMemoryExtractionQueue;
import app.dita.core.model.GroupScope;

import java.util.UUID;

/** The reference queue must satisfy its own contract (sanity check for the contract test itself). Offline. */
class InMemoryExtractionQueueContractTest extends ExtractionQueueContractTest {
    @Override protected ExtractionQueue createQueue() { return new InMemoryExtractionQueue(); }
    @Override protected GroupScope newGroup(String label) { return new GroupScope("g-" + label + "-" + UUID.randomUUID()); }
    @Override protected void seedMessages(GroupScope scope, String... ids) { }
}
