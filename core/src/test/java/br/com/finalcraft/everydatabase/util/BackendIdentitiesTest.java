package br.com.finalcraft.everydatabase.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the backend-identity derivation. Pure: the machine discriminator is injected, so
 * nothing here depends on the host name, the network, or a running server.
 */
class BackendIdentitiesTest {

    private static final String MACHINE_A = "server-a";
    private static final String MACHINE_B = "server-b";

    @Test
    @DisplayName("machine-local jdbc coordinate: same text on two machines yields distinct identities")
    void machineLocalJdbc_differentMachines_differentIdentities() {
        String url = "jdbc:mariadb://localhost:3306/mc";

        String onA = BackendIdentities.jdbc("sql", url, MACHINE_A);
        String onB = BackendIdentities.jdbc("sql", url, MACHINE_B);

        assertNotEquals(onA, onB);
        assertEquals(onA, BackendIdentities.jdbc("sql", url, MACHINE_A));
    }

    @Test
    @DisplayName("machine-local directory: same path on two machines yields distinct identities")
    void machineLocalDirectory_differentMachines_differentIdentities() {
        String onA = BackendIdentities.directory("localfile", Paths.get("/home/mc/data"), MACHINE_A);
        String onB = BackendIdentities.directory("localfile", Paths.get("/home/mc/data"), MACHINE_B);

        assertNotEquals(onA, onB);
        assertEquals(onA, BackendIdentities.directory("localfile", Paths.get("/home/mc/data"), MACHINE_A));
    }

    @Test
    @DisplayName("routable coordinate ignores the machine discriminator")
    void routableCoordinate_ignoresMachine() {
        String url = "jdbc:mariadb://db.example.com:3306/mc";

        assertEquals(BackendIdentities.jdbc("sql", url, MACHINE_A),
                     BackendIdentities.jdbc("sql", url, MACHINE_B));
    }

    @Test
    @DisplayName("the type prefix keeps different backend kinds apart on equal coordinates")
    void typePrefix_separatesBackendKinds() {
        assertNotEquals(BackendIdentities.directory("localfile", Paths.get("data"), MACHINE_A),
                        BackendIdentities.directory("groupedfile", Paths.get("data"), MACHINE_A));
    }

    @Test
    @DisplayName("credentials embedded in a jdbc url never reach the identity")
    void jdbcCredentials_areStripped() {
        String identity = BackendIdentities.jdbc(
                "sql", "jdbc:mariadb://root:s3cr3t@db.example.com:3306/mc?user=root&password=s3cr3t", MACHINE_A);

        assertFalse(identity.contains("s3cr3t"), identity);
        assertFalse(identity.contains("root"), identity);
        assertTrue(identity.contains("db.example.com:3306/mc"), identity);
    }

    @Test
    @DisplayName("credentials embedded in a mongo connection string never reach the identity")
    void mongoCredentials_areStripped() {
        String identity = BackendIdentities.mongo(
                "mongo", "mongodb://admin:hunter2@mongo.example.com:27017/?authSource=admin", "mc", MACHINE_A);

        assertFalse(identity.contains("hunter2"), identity);
        assertFalse(identity.contains("admin"), identity);
        assertEquals("mongo:mongodb://mongo.example.com:27017/mc", identity);
    }

    @Test
    @DisplayName("mongo: a routable seed makes the deployment shareable, an all-loopback seed list does not")
    void mongoSeeds_classification() {
        assertEquals(BackendIdentities.mongo("mongo", "mongodb://mongo.example.com:27017", "mc", MACHINE_A),
                     BackendIdentities.mongo("mongo", "mongodb://mongo.example.com:27017", "mc", MACHINE_B));
        assertNotEquals(BackendIdentities.mongo("mongo", "mongodb://localhost:27017", "mc", MACHINE_A),
                        BackendIdentities.mongo("mongo", "mongodb://localhost:27017", "mc", MACHINE_B));
    }

    @Test
    @DisplayName("an embedded jdbc url without an authority is machine-local")
    void embeddedJdbcUrl_isMachineLocal() {
        assertNotEquals(BackendIdentities.jdbc("sql", "jdbc:h2:file:./data/db", MACHINE_A),
                        BackendIdentities.jdbc("sql", "jdbc:h2:file:./data/db", MACHINE_B));
    }

    @Test
    @DisplayName("loopback host forms are machine-local, a routable name is not")
    void loopbackForms_areMachineLocal() {
        assertTrue(BackendIdentities.isMachineLocalHost("localhost"));
        assertTrue(BackendIdentities.isMachineLocalHost("127.0.0.1"));
        assertTrue(BackendIdentities.isMachineLocalHost("[::1]"));
        assertTrue(BackendIdentities.isMachineLocalHost("LOCALHOST"));
        assertFalse(BackendIdentities.isMachineLocalHost("db.example.com"));
        assertFalse(BackendIdentities.isMachineLocalHost("10.0.0.5"));
    }

    @Test
    @DisplayName("normalisation absorbs case and a trailing slash, not the database name's case")
    void normalisation_isStable() {
        assertEquals(BackendIdentities.jdbc("sql", "jdbc:MariaDB://DB.Example.com:3306/mc/", MACHINE_A),
                     BackendIdentities.jdbc("sql", "jdbc:mariadb://db.example.com:3306/mc", MACHINE_A));
        assertNotEquals(BackendIdentities.jdbc("sql", "jdbc:mariadb://db.example.com:3306/MC", MACHINE_A),
                        BackendIdentities.jdbc("sql", "jdbc:mariadb://db.example.com:3306/mc", MACHINE_A));
    }

    @Test
    @DisplayName("the local machine discriminator is stable within a process")
    void localMachine_isStable() {
        assertEquals(BackendIdentities.localMachine(), BackendIdentities.localMachine());
        assertFalse(BackendIdentities.localMachine().isEmpty());
    }
}
