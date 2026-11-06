package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.messaging.notification.application.service.NotificationRetentionJob;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository.Purge;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.module.adapter.out.persistence.JdbcPlanModuleRowStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.11 — notification inbox retention on PostgreSQL, two real tenants built by the foundation scripts: the
 * two set-based DELETEs of {@link JdbcNotificationInboxAdapter#purge} over every row of the I/O matrix (read old /
 * recent, unread old, departed or absent member, same user active in another tenant), the job end to end (schema
 * without inbox skipped), and messaging V3 (the {@code read_at} partial index) idempotent.
 */
@DisplayName("Notification retention on PostgreSQL (Story 19.11)")
class NotificationRetentionPostgresTest {

    private static final String TEST_DB = "keevo_notification_retention";
    private static final Instant NOW = Instant.now();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static UUID tenantA;
    private static UUID tenantB;
    private static final String SCHEMA_A = "kv_rtna01";
    private static final String SCHEMA_B = "kv_rtnb01";

    private JdbcNotificationInboxAdapter adapter;

    @BeforeAll
    static void database() {
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
        jdbc.update("UPDATE public.tenants SET status = 'DELETED'"); // only the tenants of this test are live
        tenantA = tenant(SCHEMA_A, "KV-RTNA01");
        tenantB = tenant(SCHEMA_B, "KV-RTNB01");
    }

    @AfterAll
    static void drop() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM \"" + SCHEMA_A + "\".notifications");
        jdbc.update("DELETE FROM \"" + SCHEMA_B + "\".notifications");
        adapter = new JdbcNotificationInboxAdapter(jdbc, new ObjectMapper());
    }

    private static UUID tenant(String schema, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')", id, code, schema);
        new TenantSchemaProvisioner(dataSource).provision(id.toString(), schema);
        return id;
    }

    private static UUID user() {
        UUID id = UUID.randomUUID();
        String phone = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')", id, phone);
        return id;
    }

    private static void member(UUID userId, UUID tenantId, boolean active) {
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), ?, 'EMPLOYEE', ?, ?)", UUID.randomUUID(), active,
                tenantId, userId);
    }

    /** One notification; {@code readDaysAgo} null = unread. */
    private static UUID row(String schema, UUID userId, int createdDaysAgo, Integer readDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".notifications (id, user_id, type, title, body, read_at, "
                + "created_at) VALUES (?, ?, 'TEST', 't', 'b', ?, ?)", id, userId,
                readDaysAgo == null ? null : Timestamp.from(NOW.minus(Duration.ofDays(readDaysAgo))),
                Timestamp.from(NOW.minus(Duration.ofDays(createdDaysAgo))));
        return id;
    }

    private static List<UUID> ids(String schema) {
        return jdbc.queryForList("SELECT id FROM \"" + schema + "\".notifications", UUID.class);
    }

    @Test
    @DisplayName("matrix: read 100 days ago deleted, read 10 days ago kept, unread 400 days old kept, departed and "
            + "absent members' rows deleted, the same user active in the other tenant keeps his rows there")
    void purgeMatrix() {
        UUID active = user();
        member(active, tenantA, true);
        UUID leftA = user(); // left A, still active in B
        member(leftA, tenantA, false);
        member(leftA, tenantB, true);
        UUID stranger = user(); // no membership in A at all

        UUID readOld = row(SCHEMA_A, active, 120, 100);
        UUID readRecent = row(SCHEMA_A, active, 20, 10);
        UUID unreadOld = row(SCHEMA_A, active, 400, null);
        row(SCHEMA_A, leftA, 1, null);
        row(SCHEMA_A, leftA, 5, 2);
        row(SCHEMA_A, stranger, 1, null);
        UUID inB = row(SCHEMA_B, leftA, 1, null);

        Purge purge = adapter.purge(SCHEMA_A, tenantA, NOW.minus(Duration.ofDays(90)));

        assertThat(purge.read()).isEqualTo(1);
        assertThat(purge.departed()).isEqualTo(3);
        assertThat(ids(SCHEMA_A)).containsExactlyInAnyOrder(readRecent, unreadOld);
        assertThat(ids(SCHEMA_A)).doesNotContain(readOld);
        assertThat(ids(SCHEMA_B)).containsExactly(inB);

        assertThat(adapter.purge(SCHEMA_A, tenantA, NOW.minus(Duration.ofDays(90))).total()).isZero(); // idempotent
    }

    @Test
    @DisplayName("schemasWithInbox: the provisioned tenants, not a schema without the messaging tables")
    void schemasWithInbox() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS kv_rtnc01");
        assertThat(adapter.schemasWithInbox()).contains(SCHEMA_A, SCHEMA_B).doesNotContain("kv_rtnc01", "public");
    }

    @Test
    @DisplayName("job end to end: both tenants purged in their own transaction, a live schema without inbox skipped")
    void jobEndToEnd() {
        UUID owner = user();
        member(owner, tenantA, true);
        member(owner, tenantB, true);
        row(SCHEMA_A, owner, 200, 150);
        UUID keptA = row(SCHEMA_A, owner, 1, null);
        row(SCHEMA_B, UUID.randomUUID(), 1, null); // a user who never was a member
        UUID keptB = row(SCHEMA_B, owner, 30, 30);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS kv_rtnd01");
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) VALUES (?, now(), now(), 'KV-RTND01', 3, 500, 1, "
                + "'FREE', 'kv_rtnd01', 'ACTIVE', 'Test') ON CONFLICT DO NOTHING", UUID.randomUUID());

        NotificationRetentionJob job = new NotificationRetentionJob(new JdbcPlanModuleRowStore(dataSource), adapter,
                new DataSourceTransactionManager(dataSource), Clock.fixed(NOW, ZoneOffset.UTC), 90);
        NotificationRetentionJob.Report report = job.purge();

        assertThat(report.tenants()).isEqualTo(2);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failures()).isZero();
        assertThat(report.read()).isEqualTo(1);
        assertThat(report.departed()).isEqualTo(1);
        assertThat(ids(SCHEMA_A)).containsExactly(keptA);
        assertThat(ids(SCHEMA_B)).containsExactly(keptB);
    }

    @Test
    @DisplayName("messaging V3: idx_notifications_read_at (partial, read_at IS NOT NULL) present once; the script run "
            + "twice more changes nothing")
    void v3Idempotent() throws Exception {
        String sql = new ClassPathResource("db/modules/messaging/V3__notifications_retention_index.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> before = indexes(SCHEMA_A);
        assertThat(before).anyMatch(def -> def.contains("idx_notifications_read_at")
                && def.contains("(read_at)") && def.contains("WHERE (read_at IS NOT NULL)"));
        for (int i = 0; i < 2; i++) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL search_path TO " + SCHEMA_A);
                    st.execute(sql);
                }
                conn.commit();
            }
        }
        assertThat(indexes(SCHEMA_A)).isEqualTo(before);
    }

    private static List<String> indexes(String schema) {
        return jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = 'notifications' "
                + "ORDER BY indexname", String.class, schema);
    }
}
