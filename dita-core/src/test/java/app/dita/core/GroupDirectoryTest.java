package app.dita.core;

import app.dita.core.model.GroupDirectory;
import app.dita.core.model.GroupDirectory.ChatType;
import app.dita.core.model.GroupScope;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroupDirectoryTest {   // Natasha N-4
    final Set<String> active = new HashSet<>(Set.of("g-parents"));
    final GroupDirectory dir = new GroupDirectory(active::contains);

    @Test void privateChatsAndChannelsNeverGetAScope() {
        dir.register("telegram", "-100", ChatType.SUPERGROUP, "g-parents");
        assertTrue(dir.resolve("telegram", "-100", ChatType.PRIVATE).isEmpty());
        assertTrue(dir.resolve("telegram", "-100", ChatType.CHANNEL).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> dir.register("telegram", "42", ChatType.PRIVATE, "g-x"));
    }

    @Test void migrationKeepsTheSameMemory() {
        dir.register("telegram", "-55", ChatType.GROUP, "g-parents");
        dir.migrate("telegram", "-55", "-100555");
        assertEquals(Optional.of(new GroupScope("g-parents")), dir.resolve("telegram", "-100555", ChatType.SUPERGROUP));
    }

    @Test void inactiveOrUnknownGroupsDoNotResolve() {
        dir.register("telegram", "-100", ChatType.SUPERGROUP, "g-parents");
        assertTrue(dir.resolve("telegram", "-999", ChatType.SUPERGROUP).isEmpty());
        active.remove("g-parents");   // bot removed → retention; no reads or writes
        assertTrue(dir.resolve("telegram", "-100", ChatType.SUPERGROUP).isEmpty());
    }
}
