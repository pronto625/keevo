package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.module.adapter.out.persistence.FlywayModuleMigrations;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 24.12 — the cluster-wide tenant DDL lock ({@link TenantDdlLock}) on PostgreSQL 16: the tenant creation, every
 * module script run by Flyway and the schema drops wait for it, and tenants created in parallel (the Bref cutover at
 * parallelism 8) no longer deadlock in the foundation scripts' catalogue scan.
 */
@DisplayName("Tenant DDL lock on PostgreSQL (Story 24.12)")
class TenantDdlLockPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_tenant_ddl_lock";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TenantSchemaProvisioner provisioner;
    private static FlywayModuleMigrations migrations;

    @BeforeAll
    static void migratePublic() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration")
                .load().migrate();
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
        provisioner = new TenantSchemaProvisioner(dataSource);
        migrations = new FlywayModuleMigrations(dataSource);
    }

    @AfterAll
    static void dropTestDatabase() {
        migrations.close();
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    /** Runs {@code work} while another session holds the lock: it must wait, then finish once the lock is released. */
    private static <T> T blockedThenReleased(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            TenantDdlLock.acquire(holder);
            Future<T> future = pool.submit(work);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int waiting = 0;
            while (waiting == 0 && System.nanoTime() < deadline && !future.isDone()) {
                Thread.sleep(100);
                waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
                        + " AND database = (SELECT oid FROM pg_database WHERE datname = current_database())",
                        Integer.class);
            }
            assertThat(waiting).as("one session waits for the tenant DDL lock").isEqualTo(1);
            assertThat(future.isDone()).as("waits for the tenant DDL lock").isFalse();
            holder.commit(); // releases the lock
            return future.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the creation of a tenant schema waits for the lock")
    void creationWaitsForTheLock() throws Exception {
        blockedThenReleased(() -> {
            provisioner.provision("t-1", "kv_ddl001");
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'kv_ddl001'",
                Integer.class)).isPositive();
    }

    @Test
    @DisplayName("each module script run by Flyway waits for the lock")
    void flywayScriptsWaitForTheLock() throws Exception {
        provisioner.provision("t-2", "kv_ddl002");
        var outcome = blockedThenReleased(() -> migrations.migrate("kv_ddl002", "core"));
        assertThat(outcome.applied()).isPositive();
    }

    @Test
    @DisplayName("the drop of a tenant schema waits for the lock")
    void dropWaitsForTheLock() throws Exception {
        provisioner.provision("t-3", "kv_ddl003");
        blockedThenReleased(() -> {
            provisioner.dropSchemaForDeletion("kv_ddl003");
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = 'kv_ddl003'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("the lock needs an open transaction")
    void refusedInAutoCommit() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            assertThatThrownBy(() -> TenantDdlLock.acquire(connection)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("eight tenants created and provisioned in parallel: no deadlock, every script applied")
    void parallelCreationsDoNotDeadlock() throws Exception {
        int tenants = 8;
        ExecutorService pool = Executors.newFixedThreadPool(tenants);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < tenants; i++) {
                String schema = "kv_par%03d".formatted(i);
                futures.add(pool.submit(() -> {
                    provisioner.provision("p", schema);
                    int applied = 0;
                    for (String module : List.of("core", "store", "catalog", "crm", "inventory", "pos", "messaging",
                            "reporting", "sync")) {
                        applied += migrations.migrate(schema, module).applied();
                    }
                    return applied;
                }));
            }
            for (Future<Integer> future : futures) {
                assertThat(future.get(5, TimeUnit.MINUTES)).isPositive(); // a deadlock would throw here
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
