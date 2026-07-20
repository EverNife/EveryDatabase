package br.com.finalcraft.everydatabase.modules;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.SyncParticipation;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryConfig;
import br.com.finalcraft.everydatabase.modules.mongo.MongoConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code isMachineLocalIdentity()} / {@code syncParticipation()} classification per backend.
 *
 * <p>These read config only - no connection is opened - so every backend can be constructed and
 * questioned without a running server. The load-bearing invariant across all of them: a
 * {@code sharedIdentity} always makes the store shareable ({@code isMachineLocalIdentity() == false}),
 * even a file backend whose coordinate is otherwise machine-local by definition.
 */
class MachineLocalIdentityTest {

    @Test
    @DisplayName("a shared identity forces isMachineLocalIdentity() == false on every backend")
    void sharedIdentity_forcesShareable_onEveryBackend() {
        Storage sql = Storages.createSQL(
                new SqlConfig("jdbc:mariadb://localhost:3306/mc", "u", "p", null, "shared"));
        Storage mongo = Storages.createMongo(
                new MongoConfig("mongodb://localhost:27017", "mc", java.util.Optional.empty(), "shared"));
        Storage local = Storages.createLocalFile(new LocalFileConfig(Paths.get("data"), "shared"));
        Storage grouped = Storages.createGroupedFile(new GroupedFileConfig(Paths.get("data"), "shared"));
        Storage h2 = Storages.createH2(new SqlConfig("jdbc:h2:mem:test", "", "", null, "shared"));
        Storage mem = Storages.createInMemory(new InMemoryConfig("shared"));

        for (Storage s : new Storage[]{sql, mongo, local, grouped, h2, mem}) {
            assertFalse(s.isMachineLocalIdentity(),
                    s.getClass().getSimpleName() + " with a shared identity must be shareable");
        }
    }

    @Test
    @DisplayName("without a shared identity, loopback/file/memory backends are machine-local")
    void noSharedIdentity_machineLocalBackends() {
        assertTrue(Storages.createSQL(new SqlConfig("jdbc:mariadb://localhost:3306/mc", "u", "p"))
                .isMachineLocalIdentity());
        assertFalse(Storages.createSQL(new SqlConfig("jdbc:mariadb://db.example.com:3306/mc", "u", "p"))
                .isMachineLocalIdentity(), "a routable host is shareable");
        assertTrue(Storages.createLocalFile(new LocalFileConfig(Paths.get("data"))).isMachineLocalIdentity());
        assertTrue(Storages.createGroupedFile(new GroupedFileConfig(Paths.get("data"))).isMachineLocalIdentity());
        assertTrue(Storages.createH2(new SqlConfig("jdbc:h2:mem:test", "", "")).isMachineLocalIdentity());
        assertTrue(Storages.createInMemory().isMachineLocalIdentity());
    }

    @Test
    @DisplayName("syncParticipation() passes the config value through, defaulting to RECOMMENDED")
    void syncParticipation_passesThrough() {
        assertEquals(SyncParticipation.RECOMMENDED, Storages.createInMemory().syncParticipation());
        assertEquals(SyncParticipation.NEVER,
                Storages.createInMemory(new InMemoryConfig("x", SyncParticipation.NEVER)).syncParticipation());
        assertEquals(SyncParticipation.ALWAYS,
                Storages.createLocalFile(new LocalFileConfig(Paths.get("data"), "x", SyncParticipation.ALWAYS))
                        .syncParticipation());
    }
}
