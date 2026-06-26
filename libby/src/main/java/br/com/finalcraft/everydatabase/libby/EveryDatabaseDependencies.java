package br.com.finalcraft.everydatabase.libby;

import br.com.finalcraft.everydatabase.libby.util.LibraryFactory;
import net.byteflux.libby.Library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Ready-made dependency bundles for the {@code everydatabase-libby} distribution
 * flavor. Each {@code loadX} method downloads (on first run) and injects into the
 * consumer's classloader the canonical, <em>unrelocated</em> jars that
 * {@code everydatabase-core} compiles against.
 *
 * <p>Call one of these once at startup (e.g. a plugin's {@code onLoad}), before
 * touching any {@code br.com.finalcraft.everydatabase} storage API:</p>
 * <pre>{@code
 * DependencyManager manager = new DependencyManager(getName(), getDataFolder(), "libs", getClassLoader());
 * EveryDatabaseDependencies.loadAll(manager);   // or a narrower bundle, see below
 * // ... Storages.createH2(...), Storages.createMongo(...), etc. now work
 * }</pre>
 *
 * <p>{@link #loadAll(DependencyManager)} covers every backend. The granular
 * bundles let slim setups skip what they do not use; each one is self-sufficient
 * for its named feature (re-listing a jar across bundles is harmless: already
 * downloaded files are reused and already added classpath URLs are ignored).</p>
 *
 * <p>JDBC drivers (MySQL/MariaDB and PostgreSQL) are part of
 * {@link #loadAll(DependencyManager)} - they ship by default in every
 * distribution flavor. Slim setups that compose granular bundles instead can
 * still pull them individually via {@link #loadMySqlDriver(DependencyManager)}
 * and {@link #loadPostgresDriver(DependencyManager)}.</p>
 *
 * <p>Libby 1.2.0 does not resolve transitive dependencies, so the lists below
 * enumerate the full flat dependency tree of {@code everydatabase-core}.</p>
 */
public final class EveryDatabaseDependencies {

    // =================================================================================
    //  Versions come from DependencyVersions, which is GENERATED from
    //  gradle/libs.versions.toml (the :libby:generateDependencyVersions task), so they
    //  cannot drift from what everydatabase-core compiles against. Only the flat artifact
    //  list lives here: Libby does NOT resolve transitives, so every needed jar is
    //  enumerated explicitly, each stamped with its catalog-sourced version.
    // =================================================================================

    /** Jackson JSON stack: required by {@code JacksonJsonCodec} (and by the YAML codec, which builds on it).
     *  Includes the datatype modules ({@code jsr310} for {@code java.time}, {@code jdk8} for {@code Optional})
     *  that {@code JacksonConfig} registers into the default codec mappers. */
    private static final String[] JACKSON_JSON_STACK = {
            "com.fasterxml.jackson.core:jackson-core:" + DependencyVersions.JACKSON,
            "com.fasterxml.jackson.core:jackson-annotations:" + DependencyVersions.JACKSON,
            "com.fasterxml.jackson.core:jackson-databind:" + DependencyVersions.JACKSON,
            "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:" + DependencyVersions.JACKSON,
            "com.fasterxml.jackson.datatype:jackson-datatype-jdk8:" + DependencyVersions.JACKSON,
    };

    /** YAML additions on top of {@link #JACKSON_JSON_STACK}: required by {@code JacksonYamlCodec}. */
    private static final String[] JACKSON_YAML_EXTRAS = {
            "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:" + DependencyVersions.JACKSON,
            "org.yaml:snakeyaml:" + DependencyVersions.SNAKEYAML,
    };

    /** SQL pool stack shared by every SQL backend (HikariCP hard-requires slf4j-api at class-init).
     *  HikariCP 4.x = last Java 8 line; 5.x is Java 11 bytecode. */
    private static final String[] SQL_POOL_STACK = {
            "com.zaxxer:HikariCP:" + DependencyVersions.HIKARICP,
            "org.slf4j:slf4j-api:" + DependencyVersions.SLF4J,
    };

    /** The embedded H2 database engine (its JDBC driver is built in).
     *  1.4.200 = last Java 8 release; 2.x is Java 11 bytecode with an incompatible file format. */
    private static final String[] H2_ENGINE = {
            "com.h2database:h2:" + DependencyVersions.H2,
    };

    /** MongoDB synchronous driver and its flat transitive set. */
    private static final String[] MONGO_STACK = {
            "org.mongodb:mongodb-driver-sync:" + DependencyVersions.MONGODB,
            "org.mongodb:mongodb-driver-core:" + DependencyVersions.MONGODB,
            "org.mongodb:bson:" + DependencyVersions.MONGODB,
            "org.mongodb:bson-record-codec:" + DependencyVersions.MONGODB,
    };

    /** MySQL/MariaDB JDBC driver - included in {@link #loadAll(DependencyManager)} by default. */
    private static final String[] MYSQL_DRIVER = {
            "com.mysql:mysql-connector-j:" + DependencyVersions.MYSQL,
    };

    /** PostgreSQL JDBC driver - included in {@link #loadAll(DependencyManager)} by default. */
    private static final String[] POSTGRES_DRIVER = {
            "org.postgresql:postgresql:" + DependencyVersions.POSTGRESQL,
    };

    private EveryDatabaseDependencies() {
        // Static utility class.
    }

    /**
     * Loads everything {@code everydatabase-core} may need at runtime: the
     * Jackson JSON and YAML codec stacks, the SQL pool stack (HikariCP + slf4j-api),
     * the embedded H2 engine, the MongoDB driver, and the JDBC drivers for
     * MySQL/MariaDB and PostgreSQL.
     *
     * <p>Setups that want a narrower footprint can skip this method and compose
     * the granular bundles instead ({@link #loadJacksonJson(DependencyManager)},
     * {@link #loadSql(DependencyManager)}, {@link #loadMongo(DependencyManager)}, ...).</p>
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadAll(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(
                JACKSON_JSON_STACK,
                JACKSON_YAML_EXTRAS,
                SQL_POOL_STACK,
                H2_ENGINE,
                MONGO_STACK,
                MYSQL_DRIVER,
                POSTGRES_DRIVER
        ));
    }

    /**
     * Loads the Jackson JSON stack ({@code jackson-core}, {@code jackson-annotations},
     * {@code jackson-databind}) - everything {@code JacksonJsonCodec} needs.
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadJacksonJson(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(JACKSON_JSON_STACK));
    }

    /**
     * Loads the Jackson YAML stack - everything {@code JacksonYamlCodec} needs.
     * Includes the JSON stack: {@code jackson-dataformat-yaml} is a format module
     * on top of {@code jackson-databind} and cannot operate without it.
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadJacksonYaml(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(JACKSON_JSON_STACK, JACKSON_YAML_EXTRAS));
    }

    /**
     * Loads the SQL pool stack shared by every SQL backend: HikariCP plus
     * {@code slf4j-api} (which HikariCP hard-requires at class-initialization).
     *
     * <p>External SQL servers additionally need their JDBC driver on the
     * classpath - either provided by the host or loaded via
     * {@link #loadMySqlDriver(DependencyManager)} /
     * {@link #loadPostgresDriver(DependencyManager)}.</p>
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadSql(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(SQL_POOL_STACK));
    }

    /**
     * Loads everything the embedded H2 backend needs: the H2 engine (its JDBC
     * driver is built in) plus the SQL pool stack, since H2 runs through the
     * same HikariCP pool as every other SQL backend.
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadH2(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(SQL_POOL_STACK, H2_ENGINE));
    }

    /**
     * Loads the MongoDB synchronous driver with its full flat dependency set
     * ({@code mongodb-driver-sync}, {@code mongodb-driver-core}, {@code bson},
     * {@code bson-record-codec}).
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadMongo(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(MONGO_STACK));
    }

    /**
     * Loads the MySQL/MariaDB JDBC driver ({@code com.mysql:mysql-connector-j}).
     * Already included in {@link #loadAll(DependencyManager)}; use this granular
     * helper when composing a narrower bundle set (e.g. {@code loadSql} + this).
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadMySqlDriver(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(MYSQL_DRIVER));
    }

    /**
     * Loads the PostgreSQL JDBC driver ({@code org.postgresql:postgresql}).
     * Already included in {@link #loadAll(DependencyManager)}; use this granular
     * helper when composing a narrower bundle set (e.g. {@code loadSql} + this).
     *
     * @param dependencyManager the manager that downloads and injects the jars
     */
    public static void loadPostgresDriver(DependencyManager dependencyManager) {
        load(dependencyManager, toLibraries(POSTGRES_DRIVER));
    }

    /**
     * Ensures Maven Central is registered as a download repository, then loads
     * the given libraries through the parallel bulk loader.
     */
    private static void load(DependencyManager dependencyManager, Collection<Library> libraries) {
        dependencyManager.addMavenCentral();
        dependencyManager.loadLibrary(libraries);
    }

    /** Converts groups of {@code "group:artifact:version"} strings into {@link Library} instances. */
    private static List<Library> toLibraries(String[]... groups) {
        List<Library> libraries = new ArrayList<>();
        for (String[] group : groups) {
            for (String coordinates : group) {
                libraries.add(LibraryFactory.of(coordinates));
            }
        }
        return libraries;
    }

}
