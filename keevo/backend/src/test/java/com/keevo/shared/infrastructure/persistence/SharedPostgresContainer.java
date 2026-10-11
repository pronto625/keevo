package com.keevo.shared.infrastructure.persistence;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SharedPostgresContainer — one PostgreSQL 16 Testcontainer for the whole test JVM (Story v1s-15-11).
 *
 * <p>Started once on first use and stopped by Ryuk when the JVM exits. Its data directory is a
 * tmpfs (nothing written to disk) and fsync is off. Every Postgres-backed test shares it:
 * <ul>
 *   <li>Spring tests extend {@link PostgresIntegrationTest} (default database {@code test},
 *       migrated by Flyway V1..Vn on first context start, isolated by transaction rollback or
 *       by unique data);</li>
 *   <li>tests that need a pristine database (Flyway baseline, tenant DDL) call
 *       {@link #freshDatabase(String)} to (re)create a dedicated database on the same server.</li>
 * </ul>
 * {@code max_connections} is raised because each cached Spring context owns its own Hikari pool.
 */
public final class SharedPostgresContainer {

    private static final Pattern DB_NAME = Pattern.compile("[a-z][a-z0-9_]{0,40}");

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
            .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off",
                    "-c", "synchronous_commit=off", "-c", "full_page_writes=off");

    static {
        POSTGRES.start();
    }

    private SharedPostgresContainer() {}

    /**
     * Drops (after terminating its sessions, then {@code WITH (FORCE)} so a reconnecting pool cannot
     * make the DROP fail) and recreates {@code name} on the shared server.
     *
     * @return the JDBC URL of the fresh, empty database
     */
    public static String freshDatabase(String name) {
        if (!DB_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid test database name: " + name);
        }
        try (Connection conn = adminConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = '" + name + "' AND pid <> pg_backend_pid()");
            stmt.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)"); // PG13+: kills reconnecting backends too
            stmt.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test database " + name, e);
        }
        return jdbcUrl(name);
    }

    /** Terminates the sessions of {@code name} and drops it; fails loudly (IllegalStateException) if it cannot. */
    public static void dropDatabase(String name) {
        if (!DB_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid test database name: " + name);
        }
        try (Connection conn = adminConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = '" + name + "' AND pid <> pg_backend_pid()");
            stmt.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)"); // PG13+: kills reconnecting backends too
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot drop test database " + name, e);
        }
    }

    /** JDBC URL of {@code database} on the shared server. */
    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + database;
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl("postgres"), username(), password());
    }
}
