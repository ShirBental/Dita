package app.dita.store.postgres;

import app.dita.core.model.GroupScope;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;

import java.util.UUID;

/** The reference implementation must satisfy its own contract (sanity check for the contract test itself). */
class InMemoryMessageStoreContractTest extends MessageStoreContractTest {
    @Override protected MessageStore createStore() { return new InMemoryMessageStore(); }
    @Override protected GroupScope newGroup(String label) { return new GroupScope("g-" + label + "-" + UUID.randomUUID()); }
}
