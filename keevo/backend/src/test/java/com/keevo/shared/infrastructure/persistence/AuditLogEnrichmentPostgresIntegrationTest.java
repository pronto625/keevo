package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.AuditPort.AuditEntryRecord;
import com.keevo.shared.application.port.AuditPort.AuditQuery;
import com.keevo.shared.application.port.AuditPort.AuditSlice;
import com.keevo.shared.infrastructure.persistence.impl.AuditLogRepositoryAdapter;
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
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story v1s-18-6 — audit enrichment on a real PostgreSQL 16 (Flyway-migrated {@code public} +
 * provisioned / synced tenant schemas).
 *
 * <p>Adapter queries run inside the {@code @DataJpaTest} transaction against {@code public}
 * (rolled back). Tenant-schema tests use separate connections on uniquely named schemas that are
 * dropped at the end ({@code DROP SCHEMA ... CASCADE} is unaffected by the append-only trigger).
 * Mutation attempts run inside a transaction with savepoints and are rolled back, so nothing is
 * left behind in the shared database. Tests that open their own connections run outside the
 * test transaction ({@code NOT_SUPPORTED}): a TRUNCATE would otherwise wait forever on the lock
 * held by the test transaction's own reads.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(AuditLogRepositoryAdapter.class)
@DisplayName("Audit log enrichment on PostgreSQL (v1s-18-6)")
class AuditLogEnrichmentPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final List<String> NEW_COLUMNS =
            List.of("store_id", "entity_label", "device_id", "source", "metadata");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AuditLogRepositoryAdapter adapter;

    // ── V14 on public ──────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // own connections take table locks
    @DisplayName("V14: public.audit_log has the 5 columns, metadata is jsonb, triggers installed")
    void v14_appliedOnPublic() {
        assertThat(columns("public")).containsAll(NEW_COLUMNS);
        assertThat(jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'audit_log' AND column_name = 'metadata'", String.class))
                .isEqualTo("jsonb");
        assertThat(triggers("public")).contains("trg_audit_log_no_mutation", "trg_audit_log_no_truncate");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // own connections take table locks
    @DisplayName("public.audit_log rejects UPDATE, DELETE and TRUNCATE")
    void public_mutationsRejected() throws Exception {
        assertMutationsRejected("public");
    }

    // ── Tenant DDL ─────────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // own connections take table locks
    @DisplayName("provisioning a tenant creates the columns, indexes and append-only trigger")
    void tenantProvisioning_createsEnrichment() throws Exception {
        String schema = uniqueSchema();
        try {
            new TenantSchemaProvisioner(dataSource).provision(UUID.randomUUID().toString(), schema);

            assertThat(columns(schema)).containsAll(NEW_COLUMNS);
            assertThat(indexes(schema)).contains("idx_audit_log_store_occurred",
                    "idx_audit_log_user_occurred", "idx_audit_log_action_occurred", "idx_audit_log_occurred");
            assertThat(triggers(schema)).contains("trg_audit_log_no_mutation", "trg_audit_log_no_truncate");
            assertMutationsRejected(schema);
            assertThatThrownBy(() -> execInSchema(schema,
                    "INSERT INTO audit_log (user_id, entity_type, entity_id, action, source) "
                            + "VALUES (gen_random_uuid(), 'X', gen_random_uuid(), 'X', 'BOGUS')"))
                    .as("source CHECK").isInstanceOf(SQLException.class);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // own connections take table locks
    @DisplayName("boot/request sync upgrades a pre-V14 tenant: columns + trigger added, legacy row kept with NULLs")
    void tenantSync_upgradesLegacyTenant() throws Exception {
        String schema = uniqueSchema();
        UUID legacyId = UUID.randomUUID();
        try {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA \"" + schema + "\"");
                st.execute("""
                        CREATE TABLE "%s".audit_log (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL,
                            entity_type VARCHAR(50) NOT NULL, entity_id UUID NOT NULL,
                            action VARCHAR(80) NOT NULL, value_before TEXT, value_after TEXT,
                            occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW())""".formatted(schema));
                st.execute("INSERT INTO \"" + schema + "\".audit_log (id, user_id, entity_type, entity_id, action, value_after) "
                        + "VALUES ('" + legacyId + "', gen_random_uuid(), 'Product', gen_random_uuid(), 'PRODUCT_CREATED', '{}')");
            }

            new TenantSchemaSyncService(dataSource).syncNow(schema);

            assertThat(columns(schema)).containsAll(NEW_COLUMNS);
            assertThat(triggers(schema)).contains("trg_audit_log_no_mutation", "trg_audit_log_no_truncate");
            assertThat(jdbc.queryForObject("SELECT source IS NULL AND metadata IS NULL AND store_id IS NULL FROM \""
                    + schema + "\".audit_log WHERE id = ?", Boolean.class, legacyId)).isTrue();
            assertMutationsRejected(schema);

            // idempotent: a second sync (next boot) is a no-op
            new TenantSchemaSyncService(dataSource).syncNow(schema);
        } finally {
            dropSchema(schema);
        }
    }

    // ── Adapter: legacy rows, actor name, filters, keyset paging ──────────────

    @Test
    @DisplayName("legacy row (all new columns NULL) is listed and found by id; actorName falls back to the role label")
    void legacyRow_listedAndFound() {
        UUID owner = insertUser("OWNER");
        UUID entityId = UUID.randomUUID();
        UUID id = insertLegacy(owner, entityId, Instant.parse("2026-01-01T10:00:00Z"));

        AuditSlice slice = adapter.search(new AuditQuery(null, null, null, null, null, "Product", entityId),
                null, 0, 20);
        assertThat(slice.entries()).extracting(AuditEntryRecord::id).containsExactly(id);

        AuditEntryRecord r = adapter.findById(id).orElseThrow();
        assertThat(r.source()).isNull();
        assertThat(r.storeId()).isNull();
        assertThat(r.storeName()).isNull();
        assertThat(r.metadata()).isNull();
        assertThat(r.entityLabel()).isNull();
        assertThat(r.actorName()).isEqualTo("Le propriétaire");
        assertThat(r.actorPhone()).isNotNull();
        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("actorName: employee name > stored actorLabel; storeName resolved from stores")
    void actorAndStoreNames() {
        UUID storeId = insertStore("Boutique Centre");
        UUID employeeUser = insertUser("EMPLOYEE");
        insertEmployee(employeeUser, storeId, "Awa", "Diallo");
        UUID owner = insertUser("OWNER");
        Instant at = Instant.parse("2026-02-01T10:00:00Z");

        UUID byEmployee = insertEnriched(employeeUser, storeId, at, "{\"actorLabel\":\"Awa\"}");
        UUID byOwner = insertEnriched(owner, storeId, at.plusSeconds(1), "{\"actorLabel\":\"Moussa\"}");

        AuditEntryRecord e = adapter.findById(byEmployee).orElseThrow();
        assertThat(e.actorName()).isEqualTo("Awa Diallo");
        assertThat(e.storeName()).isEqualTo("Boutique Centre");
        assertThat(e.source()).isEqualTo("SYNC");
        assertThat(e.deviceId()).isEqualTo("device-D");
        assertThat(e.metadata()).contains("actorLabel");
        assertThat(adapter.findById(byOwner).orElseThrow().actorName()).isEqualTo("Moussa");
    }

    @Test
    @DisplayName("store + period filter, keyset paging to the end: every matching row exactly once, newest first, equal timestamps safe")
    void keysetPaging_equalTimestamps() {
        UUID storeId = insertStore("S");
        UUID otherStore = insertStore("Other");
        UUID user = insertUser("OWNER");
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-03-31T23:59:59Z");
        Instant same = Instant.parse("2026-03-10T08:15:30.123456Z");

        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 5; i++) expected.add(insertEnriched(user, storeId, same, null));
        expected.add(insertEnriched(user, storeId, same.plusSeconds(60), null));
        expected.add(insertEnriched(user, storeId, from, null));        // exactly at from: included
        expected.add(insertEnriched(user, storeId, to, null));          // exactly at to: included (inclusive)
        insertEnriched(user, storeId, from.minusSeconds(1), null);       // before period
        insertEnriched(user, storeId, to.plusSeconds(1), null);          // after period
        insertEnriched(user, otherStore, same, null);                    // other store

        AuditQuery q = new AuditQuery(from, to, storeId, null, null, null, null);
        List<AuditEntryRecord> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            AuditSlice slice = adapter.search(q, cursor, null, 2);
            all.addAll(slice.entries());
            assertThat(slice.hasMore()).isEqualTo(slice.nextCursor() != null);
            cursor = slice.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(all).extracting(AuditEntryRecord::id).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(all.get(0).occurredAt()).isEqualTo(to);
        for (int i = 1; i < all.size(); i++) {
            AuditEntryRecord prev = all.get(i - 1), cur = all.get(i);
            assertThat(cur.occurredAt()).isBeforeOrEqualTo(prev.occurredAt());
            if (cur.occurredAt().equals(prev.occurredAt())) {
                // PostgreSQL orders uuid byte-wise = lexicographic order of the lowercase hex text
                assertThat(cur.id().toString()).isLessThan(prev.id().toString());
            }
        }
    }

    @Test
    @DisplayName("user and action filters are applied; legacy offset paging still works")
    void userActionFilters_andOffsetPaging() {
        UUID userA = insertUser("EMPLOYEE");
        UUID userB = insertUser("EMPLOYEE");
        Instant at = Instant.parse("2026-04-01T09:00:00Z");
        UUID a1 = insertWithAction(userA, "CATEGORY_CREATED", at);
        UUID a2 = insertWithAction(userA, "CATEGORY_CREATED", at.plusSeconds(1));
        insertWithAction(userA, "CLIENT_CREATED", at.plusSeconds(2));
        insertWithAction(userB, "CATEGORY_CREATED", at.plusSeconds(3));

        AuditQuery q = new AuditQuery(null, null, null, userA, "CATEGORY_CREATED", null, null);
        AuditSlice page0 = adapter.search(q, null, 0, 1);
        AuditSlice page1 = adapter.search(q, null, 1, 1);
        assertThat(page0.entries()).extracting(AuditEntryRecord::id).containsExactly(a2);
        assertThat(page0.hasMore()).isTrue();
        assertThat(page1.entries()).extracting(AuditEntryRecord::id).containsExactly(a1);
        assertThat(page1.hasMore()).isFalse();
    }

    @Test
    @DisplayName("record(AuditEntry) persists the enrichment with metadata as jsonb")
    void record_persistsEnrichment() {
        UUID entityId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        adapter.record(new AuditPort.AuditEntry(UUID.randomUUID(), "public", "CATEGORY_CREATED", "Category",
                entityId, null, "{\"name\":\"Pagnes\"}", storeId, "Pagnes", "device-D", "SYNC",
                "{\"actorLabel\":\"Awa\"}"));

        AuditEntryRecord r = adapter.search(new AuditQuery(null, null, storeId, null, null, null, entityId),
                null, 0, 5).entries().get(0);
        assertThat(r.entityLabel()).isEqualTo("Pagnes");
        assertThat(r.source()).isEqualTo("SYNC");
        assertThat(r.deviceId()).isEqualTo("device-D");
        assertThat(r.metadata()).contains("\"actorLabel\"").contains("Awa");
        assertThat(r.actorName()).isEqualTo("Awa");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(metadata) FROM audit_log WHERE entity_id = ?",
                String.class, entityId)).isEqualTo("object");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String uniqueSchema() {
        return "kv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    private void execInSchema(String schema, String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                st.execute("SET LOCAL search_path TO \"" + schema + "\"");
                st.execute(sql);
            } finally {
                c.rollback();
            }
        }
    }

    /** Insert a row, then try UPDATE / DELETE / TRUNCATE (each rejected), all rolled back. */
    private void assertMutationsRejected(String schema) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                st.execute("SET LOCAL lock_timeout = '10s'"); // a lock wait fails fast instead of hanging
                String table = "\"" + schema + "\".audit_log";
                st.execute("INSERT INTO " + table + " (id, user_id, entity_type, entity_id, action, occurred_at) "
                        + "VALUES (gen_random_uuid(), gen_random_uuid(), 'X', gen_random_uuid(), 'X', NOW())");
                for (String sql : List.of(
                        "UPDATE " + table + " SET action = 'TAMPERED'",
                        "DELETE FROM " + table,
                        "TRUNCATE " + table)) {
                    Savepoint sp = c.setSavepoint();
                    assertThatThrownBy(() -> st.execute(sql))
                            .as("%s on %s", sql, schema)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("append-only");
                    c.rollback(sp);
                }
            } finally {
                c.rollback();
            }
        }
    }

    private List<String> columns(String schema) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'audit_log'", String.class, schema);
    }

    private List<String> indexes(String schema) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'audit_log'",
                String.class, schema);
    }

    private List<String> triggers(String schema) {
        return jdbc.queryForList("SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = 'audit_log' AND NOT t.tgisinternal", String.class, schema);
    }

    private UUID insertUser(String role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                        + "phone_number, role) VALUES (?, ?, ?, TRUE, 0, 'x', ?, ?)",
                id, now, now, "+2376" + id.toString().replace("-", "").substring(0, 8), role);
        return id;
    }

    private UUID insertStore(String name) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO stores (id, created_at, updated_at, is_active, name, type) VALUES (?, ?, ?, TRUE, ?, 'STORE')",
                id, now, now, name);
        return id;
    }

    private void insertEmployee(UUID userId, UUID storeId, String first, String last) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO employees (id, created_at, updated_at, first_name, last_name, "
                        + "password_change_required, status, store_id, user_id) VALUES (?, ?, ?, ?, ?, FALSE, 'ACTIVE', ?, ?)",
                UUID.randomUUID(), now, now, first, last, storeId, userId);
    }

    private UUID insertLegacy(UUID userId, UUID entityId, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_log (id, user_id, entity_type, entity_id, action, value_after, occurred_at) "
                + "VALUES (?, ?, 'Product', ?, 'PRODUCT_CREATED', '{\"productName\":\"x\"}', ?)",
                id, userId, entityId, Timestamp.from(at));
        return id;
    }

    private UUID insertEnriched(UUID userId, UUID storeId, Instant at, String metadata) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_log (id, user_id, entity_type, entity_id, action, value_after, occurred_at, "
                        + "store_id, entity_label, device_id, source, metadata) "
                        + "VALUES (?, ?, 'Category', ?, 'CATEGORY_CREATED', '{}', ?, ?, 'Pagnes', 'device-D', 'SYNC', CAST(? AS jsonb))",
                id, userId, UUID.randomUUID(), Timestamp.from(at), storeId, metadata);
        return id;
    }

    private UUID insertWithAction(UUID userId, String action, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_log (id, user_id, entity_type, entity_id, action, occurred_at) "
                + "VALUES (?, ?, 'Category', ?, ?, ?)", id, userId, UUID.randomUUID(), action, Timestamp.from(at));
        return id;
    }
}
