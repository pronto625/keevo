package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner.SyncKeysetIndex;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 11.5 — keyset pull indexes on a real PostgreSQL: created at provisioning for a new schema,
 * and by {@link SyncKeysetIndexJob} (CREATE INDEX CONCURRENTLY, autocommit) for an existing one,
 * without blocking writes; INVALID or wrong-definition indexes are rebuilt; a second run is a no-op.
 *
 * <p>Commits tenant schemas: each test uses its own schema names, dropped in {@link #dropSchemas()}.
 * The job is called on these schemas only ({@link SyncKeysetIndexJob#ensureIndexes(String)}), never
 * on the other tenants of the shared test database.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(TenantSchemaProvisioner.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Keyset pull indexes: provisioning + background job (Story 11.5)")
class SyncKeysetIndexJobPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final int INDEX_COUNT = TenantSchemaProvisioner.SYNC_KEYSET_INDEXES.size();

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TenantSchemaProvisioner provisioner;

    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> tenantIds = new ArrayList<>();

    @AfterEach
    void dropSchemas() {
        tenantIds.forEach(id -> jdbc.update("DELETE FROM public.tenants WHERE id = ?", id));
        schemas.forEach(s -> jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE"));
        provisioner.syncKeysetIndexes = TenantSchemaProvisioner.SYNC_KEYSET_INDEXES;
    }

    @Test
    @DisplayName("provision(): every keyset index exists, valid, with the definition the job expects")
    void provisionCreatesKeysetIndexes() {
        String schema = provision("kv_ksj001");

        Map<String, String> defs = keysetIndexes(schema);
        assertThat(defs).hasSize(INDEX_COUNT);
        assertThat(defs.get("idx_sales_sync_keyset")).endsWith("sales USING btree (updated_at, ((id)::text))");
        assertThat(defs.get("idx_stock_transfers_sync_keyset"))
                .contains("(GREATEST(occurred_at, COALESCE(updated_at, occurred_at)), ((id)::text))");
        // the job recognises what provisioning built: nothing to do
        assertThat(job().ensureIndexes(schema)).isEqualTo(new SyncKeysetIndexJob.Result(0, 0, INDEX_COUNT, 0, 0));
    }

    @Test
    @DisplayName("existing populated schema: built CONCURRENTLY while a concurrent insert goes through; 2nd run idempotent")
    void jobBuildsIndexesWithoutBlockingWrites() throws Exception {
        String schema = provision("kv_ksj002");
        dropKeysetIndexes(schema);
        jdbc.update("INSERT INTO \"" + schema + "\".clients (name, phone) "
                + "SELECT 'Client ' || g, '+2376' || lpad(g::text, 8, '0') FROM generate_series(1, 2000) g");

        CompletableFuture<SyncKeysetIndexJob.Result> run;
        // An open writing transaction makes the concurrent build wait (it waits for older transactions).
        try (Connection open = dataSource.getConnection()) {
            open.setAutoCommit(false);
            try (Statement st = open.createStatement()) {
                st.execute("INSERT INTO \"" + schema + "\".clients (name, phone) VALUES ('Ouverte', '+237600000001')");
            }
            run = CompletableFuture.supplyAsync(() -> job().ensureIndexes(schema));
            awaitBuildInProgress(schema);

            // While the build is in progress, a write on the tenant tables is not blocked.
            try (Connection writer = dataSource.getConnection(); Statement st = writer.createStatement()) {
                writer.setAutoCommit(true);
                st.execute("SET lock_timeout = '3s'");
                try {
                    st.execute("INSERT INTO \"" + schema + "\".clients (name, phone) VALUES ('Pendant', '+237600000002')");
                    st.execute("INSERT INTO \"" + schema + "\".products (name, price, sku) "
                            + "VALUES ('Produit', 100, 'SKU-KSJ-1')");
                } finally {
                    st.execute("RESET lock_timeout");
                }
            }
            open.commit();
        }

        SyncKeysetIndexJob.Result first = run.get(60, TimeUnit.SECONDS);
        assertThat(first).isEqualTo(new SyncKeysetIndexJob.Result(INDEX_COUNT, 0, 0, 0, 0));
        assertThat(keysetIndexes(schema)).hasSize(INDEX_COUNT);
        assertThat(invalidKeysetIndexes(schema)).isZero();

        assertThat(job().ensureIndexes(schema)).isEqualTo(new SyncKeysetIndexJob.Result(0, 0, INDEX_COUNT, 0, 0));
    }

    @Test
    @DisplayName("an INVALID leftover index and a wrong-definition index are dropped and rebuilt")
    void jobRepairsInvalidAndWrongIndexes() {
        String schema = provision("kv_ksj003");
        // interrupted concurrent build → INVALID index (simulated through the catalog)
        jdbc.update("UPDATE pg_index SET indisvalid = false WHERE indexrelid = ?::regclass",
                schema + ".idx_products_sync_keyset");
        // same name, other definition
        jdbc.execute("DROP INDEX \"" + schema + "\".idx_sales_sync_keyset");
        jdbc.execute("CREATE INDEX idx_sales_sync_keyset ON \"" + schema + "\".sales (created_at)");
        assertThat(invalidKeysetIndexes(schema)).isEqualTo(1);

        SyncKeysetIndexJob.Result result = job().ensureIndexes(schema);

        assertThat(result).isEqualTo(new SyncKeysetIndexJob.Result(0, 2, INDEX_COUNT - 2, 0, 0));
        assertThat(invalidKeysetIndexes(schema)).isZero();
        assertThat(keysetIndexes(schema).get("idx_sales_sync_keyset"))
                .endsWith("sales USING btree (updated_at, ((id)::text))");
        assertThat(keysetIndexes(schema).get("idx_products_sync_keyset"))
                .endsWith("products USING btree (updated_at, ((id)::text))");
    }

    @Test
    @DisplayName("runAll(): an ACTIVE tenant gets its indexes back; DELETED and schema-less tenants are ignored")
    void runAllCoversActiveTenantsOnly() {
        String active = provision("kv_ksj004");
        String deleted = provision("kv_ksj005");
        dropKeysetIndexes(active);
        dropKeysetIndexes(deleted);
        registerTenant(active, "KV-KSJ004", "ACTIVE");
        registerTenant(deleted, "KV-KSJ005", "DELETED");
        registerTenant("kv_ksj006", "KV-KSJ006", "ACTIVE"); // schema never created
        schemas.add("kv_ksj006");

        job().runAll();

        assertThat(keysetIndexes(active)).hasSize(INDEX_COUNT);
        assertThat(keysetIndexes(deleted)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'kv_ksj006'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("runAll(): no schema is started once the run budget is spent")
    void runAllStopsAtBudget() {
        String schema = provision("kv_ksj007");
        dropKeysetIndexes(schema);
        registerTenant(schema, "KV-KSJ007", "ACTIVE");
        SyncKeysetIndexJob job = job();
        job.runBudget = java.time.Duration.ZERO;

        assertThat(job.runAll()).isEqualTo(SyncKeysetIndexJob.Result.NONE);
        assertThat(keysetIndexes(schema)).isEmpty();
    }

    @Test
    @DisplayName("provision(): a failing keyset index is logged and skipped, the others exist")
    void provisionSurvivesAFailingIndex() {
        List<SyncKeysetIndex> withBogus = new ArrayList<>();
        withBogus.add(new SyncKeysetIndex("idx_bogus_sync_keyset", "no_such_table", "updated_at"));
        withBogus.addAll(TenantSchemaProvisioner.SYNC_KEYSET_INDEXES);
        provisioner.syncKeysetIndexes = withBogus;

        String schema = provision("kv_ksj008");

        assertThat(keysetIndexes(schema)).hasSize(INDEX_COUNT).doesNotContainKey("idx_bogus_sync_keyset");
    }

    @Test
    @DisplayName("a table missing from the schema is counted as skipped, not failed")
    void missingTableSkipped() {
        String schema = provision("kv_ksj009");
        jdbc.execute("DROP TABLE \"" + schema + "\".reports CASCADE");

        assertThat(job().ensureIndexes(schema))
                .isEqualTo(new SyncKeysetIndexJob.Result(0, 0, INDEX_COUNT - 1, 1, 0));
    }

    @Test
    @DisplayName("an invalid schema name is never interpolated")
    void invalidSchemaNameSkipped() {
        assertThat(job().ensureIndexes("public; DROP TABLE x")).isEqualTo(SyncKeysetIndexJob.Result.NONE);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SyncKeysetIndexJob job() {
        return new SyncKeysetIndexJob(dataSource, mock(LockProvider.class), false);
    }

    private String provision(String schema) {
        schemas.add(schema);
        provisioner.provision("tenant-" + schema, schema);
        return schema;
    }

    private void registerTenant(String schema, String code, String status) {
        UUID id = UUID.randomUUID();
        tenantIds.add(id);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) "
                + "VALUES (?, now(), now(), ?, 1, 1, 1, 'FREE', ?, ?)", id, code, schema, status);
    }

    private void dropKeysetIndexes(String schema) {
        for (SyncKeysetIndex index : TenantSchemaProvisioner.SYNC_KEYSET_INDEXES) {
            jdbc.execute("DROP INDEX \"" + schema + "\"." + index.name());
        }
        assertThat(keysetIndexes(schema)).isEmpty();
    }

    private Map<String, String> keysetIndexes(String schema) {
        return jdbc.queryForList(
                        "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND indexname LIKE '%_sync_keyset'",
                        schema).stream()
                .collect(Collectors.toMap(r -> (String) r.get("indexname"), r -> (String) r.get("indexdef")));
    }

    private int invalidKeysetIndexes(String schema) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname LIKE '%_sync_keyset' AND NOT i.indisvalid",
                Integer.class, schema);
        return n == null ? 0 : n;
    }

    /** Waits until a CREATE INDEX CONCURRENTLY runs on a table of the schema. */
    private void awaitBuildInProgress(String schema) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_progress_create_index p "
                    + "JOIN pg_class c ON c.oid = p.relid JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND p.command = 'CREATE INDEX CONCURRENTLY'",
                    Integer.class, schema);
            if (n != null && n > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no concurrent index build observed on " + schema);
    }
}
