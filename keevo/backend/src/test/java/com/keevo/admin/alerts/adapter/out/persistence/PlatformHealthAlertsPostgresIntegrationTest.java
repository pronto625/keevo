package com.keevo.admin.alerts.adapter.out.persistence;

import com.keevo.admin.alerts.domain.model.AlertSourceRows.DeletionPending;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ExpiredPlan;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.HighFeedback;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ReportFailures;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;
import com.keevo.admin.platform.adapter.out.persistence.JdbcHealthSampleRepository;
import com.keevo.admin.platform.adapter.out.persistence.JdbcPlatformProbeAdapter;
import com.keevo.admin.platform.domain.model.HealthSample;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9-5 — SQL of the health samples, the database probes, the alert sources, the alert acks and
 * the sync silence notices on a real PostgreSQL (V20 migration included). Commits (no transaction
 * around the per-company loops): unique schemas / ids, no global count asserted.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({TenantSchemaProvisioner.class, JdbcHealthSampleRepository.class, JdbcPlatformProbeAdapter.class,
        JdbcAlertSourcesAdapter.class, JdbcAlertAckRepository.class, JdbcSyncSilenceNoticeRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Health & alerts SQL on PostgreSQL (Story 9-5)")
class PlatformHealthAlertsPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired JdbcHealthSampleRepository samples;
    @Autowired JdbcPlatformProbeAdapter probe;
    @Autowired JdbcAlertSourcesAdapter sources;
    @Autowired JdbcAlertAckRepository acks;
    @Autowired JdbcSyncSilenceNoticeRepository notices;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> feedbackIds = new ArrayList<>();
    private final List<String> devices = new ArrayList<>();
    private final List<String> ackKeys = new ArrayList<>();
    private final List<Instant> sampleTimes = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        devices.forEach(d -> jdbc.update("DELETE FROM public.user_sync_state WHERE device_id = ?", d));
        feedbackIds.forEach(f -> jdbc.update("DELETE FROM public.feedback WHERE id = ?", f));
        ackKeys.forEach(k -> jdbc.update("DELETE FROM public.admin_alert_acks WHERE alert_key = ?", k));
        sampleTimes.forEach(t -> jdbc.update("DELETE FROM public.platform_health_samples WHERE sampled_at = ?",
                Timestamp.from(t)));
        tenantIds.forEach(id -> {
            jdbc.update("DELETE FROM public.sync_silence_notices WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.tenant_plan_events WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", id);
        });
        schemas.forEach(s -> jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE"));
    }

    // ── Health samples & probes ───────────────────────────────────────────────

    @Test
    void healthSamples() {
        Instant base = Instant.parse("2099-01-01T00:00:00Z");
        HealthSample older = sample(base, true, 100, 2, 40);
        HealthSample down = sample(base.plus(Duration.ofMinutes(5)), false, 0, 0, null);
        HealthSample newest = sample(base.plus(Duration.ofMinutes(10)), true, 50, 1, 900);
        samples.insert(older);
        samples.insert(down);
        samples.insert(newest);
        samples.insert(newest); // same instant: kept, no error

        assertThat(samples.latest()).contains(newest);
        assertThat(samples.recent(base, 12)).containsExactly(newest, down, older);
        assertThat(samples.recent(base, 2)).containsExactly(newest, down);
        assertThat(samples.countOkSince(base)).isEqualTo(2);
        assertThat(samples.firstSampleAt()).isPresent();

        Instant ancient = Instant.parse("1980-01-01T00:00:00Z");
        samples.insert(sample(ancient, true, 1, 0, 10));
        assertThat(samples.purgeBefore(Instant.parse("1990-01-01T00:00:00Z"))).isEqualTo(1);
    }

    @Test
    void probes() {
        assertThat(probe.databaseOk()).isTrue();

        String a = tenant("kv_k95h01", "Boutique A", "ACTIVE", "FREE");
        String b = tenant("kv_k95h02", "Boutique B", "ACTIVE", "FREE");
        tenant("kv_k95h03", "Boutique cassée", "ACTIVE", "FREE");
        jdbc.execute("DROP TABLE kv_k95h03.sync_error_log");
        refusal(a, Instant.now().minusSeconds(60));
        refusal(a, Instant.now().minusSeconds(120));
        refusal(b, Instant.now().minusSeconds(30));
        refusal(b, Instant.now().minus(Duration.ofHours(2))); // outside the hour

        assertThat(probe.syncRefusalsSince(Instant.now().minus(Duration.ofHours(1)))).isGreaterThanOrEqualTo(3);
        long before = probe.syncRefusalsSince(Instant.now().minus(Duration.ofHours(1)));
        refusal(a, Instant.now());
        assertThat(probe.syncRefusalsSince(Instant.now().minus(Duration.ofHours(1)))).isEqualTo(before + 1);
    }

    // ── Alert sources ─────────────────────────────────────────────────────────

    @Test
    void highFeedback() {
        tenant("kv_k95f01", "Boutique Awa", "ACTIVE", "FREE");
        UUID high = feedback("kv_k95f01", "HIGH", Instant.now().minus(Duration.ofDays(1)), false);
        UUID normal = feedback("kv_k95f01", "NORMAL", Instant.now(), false);
        UUID handled = feedback("kv_k95f01", "HIGH", Instant.now(), true);
        UUID old = feedback("kv_k95f01", "HIGH", Instant.now().minus(Duration.ofDays(40)), false);
        UUID orphan = feedback("kv_k95zzz", "HIGH", Instant.now(), false);

        List<HighFeedback> rows = sources.unhandledHighFeedbackSince(Instant.now().minus(Duration.ofDays(30)))
                .stream().filter(f -> feedbackIds.contains(f.id())).toList();

        assertThat(rows).extracting(HighFeedback::id).containsExactlyInAnyOrder(high, orphan);
        assertThat(rows).extracting(HighFeedback::id).doesNotContain(normal, handled, old);
        assertThat(rows.stream().filter(r -> r.id().equals(high)).findFirst().orElseThrow().tenantName())
                .isEqualTo("Boutique Awa");
        assertThat(rows.stream().filter(r -> r.id().equals(orphan)).findFirst().orElseThrow().tenantName())
                .isEqualTo("Entreprise supprimée");
    }

    @Test
    void deletionPending() {
        tenant("kv_k95d01", "Boutique Partante", "DELETION_PENDING", "FREE");
        UUID id = tenantIds.get(0);
        Instant scheduled = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("UPDATE public.tenants SET deletion_scheduled_at = ? WHERE id = ?", Timestamp.from(scheduled), id);
        tenant("kv_k95d02", "Boutique Active", "ACTIVE", "FREE");

        List<DeletionPending> rows = sources.deletionPending().stream()
                .filter(d -> tenantIds.contains(d.tenantId())).toList();

        assertThat(rows).containsExactly(new DeletionPending(id, "Boutique Partante", scheduled));
    }

    @Test
    void expiredNotFree() {
        String expired = tenant("kv_k95e01", "Boutique Expirée", "ACTIVE", "PREMIUM");
        String valid = tenant("kv_k95e02", "Boutique Valide", "ACTIVE", "PREMIUM");
        String free = tenant("kv_k95e03", "Boutique Gratuite", "ACTIVE", "FREE");
        String broken = tenant("kv_k95e04", "Boutique cassée", "ACTIVE", "PREMIUM");
        String gone = tenant("kv_k95e05", "Boutique supprimée", "DELETED", "PREMIUM");
        String justExpired = tenant("kv_k95e06", "Boutique du jour", "ACTIVE", "PREMIUM");
        String suspendedRow = tenant("kv_k95e07", "Boutique ligne suspendue", "ACTIVE", "PREMIUM");
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofHours(26)); // one missed nightly downgrade
        Instant past = now.minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MILLIS);
        subscription(expired, "PREMIUM", "ACTIVE", past);
        subscription(valid, "PREMIUM", "ACTIVE", now.plus(Duration.ofDays(3)));
        subscription(free, "FREE", "ACTIVE", past);
        subscription(gone, "PREMIUM_TRIAL", "ACTIVE", past);
        subscription(justExpired, "PREMIUM", "ACTIVE", now.minus(Duration.ofHours(3))); // before the 02:00 run
        subscription(suspendedRow, "PREMIUM", "SUSPENDED", past);
        jdbc.execute("DROP TABLE " + broken + ".subscriptions");

        List<ExpiredPlan> rows = sources.expiredPlansNotFree(cutoff).stream()
                .filter(p -> tenantIds.contains(p.tenantId())).toList();

        assertThat(rows).containsExactly(new ExpiredPlan(tenantIds.get(0), "Boutique Expirée", "PREMIUM", past));
    }

    @Test
    void reportFailures() {
        String a = tenant("kv_k95r01", "Boutique A", "ACTIVE", "FREE");
        String b = tenant("kv_k95r02", "Boutique B", "ACTIVE", "FREE");
        Instant since = Instant.now().minus(Duration.ofHours(1));
        ReportFailures before = sources.reportFailuresSince(since);
        for (int i = 0; i < 7; i++) report(a, "FAILED", Instant.now().minusSeconds(60L * i));
        for (int i = 0; i < 5; i++) report(b, "FAILED", Instant.now().minusSeconds(30L * i));
        report(b, "SENT", Instant.now());
        report(b, "FAILED", Instant.now().minus(Duration.ofHours(3)));

        ReportFailures after = sources.reportFailuresSince(since);

        assertThat(after.failures() - before.failures()).isEqualTo(12);
        assertThat(after.companies()).isGreaterThanOrEqualTo(2);
        assertThat(after.latestAt()).isNotNull();
    }

    @Test
    void silentTenantsAndNotices() {
        String silent = tenant("kv_k95s01", "Boutique Silencieuse", "ACTIVE", "FREE");
        UUID silentId = tenantIds.get(0);
        String recent = tenant("kv_k95s02", "Boutique Active", "ACTIVE", "FREE");
        tenant("kv_k95s03", "Boutique Jamais", "ACTIVE", "FREE"); // never synced: no device row
        String suspended = tenant("kv_k95s04", "Boutique Suspendue", "SUSPENDED", "FREE");
        String partial = tenant("kv_k95s05", "Boutique Mixte", "ACTIVE", "FREE");
        String newDevice = tenant("kv_k95s06", "Boutique Nouvel Appareil", "ACTIVE", "FREE");

        Instant oldPush = Instant.now().minus(Duration.ofDays(6)).truncatedTo(ChronoUnit.MILLIS);
        device(silent, oldPush);
        device(silent, oldPush.minus(Duration.ofDays(2)));
        device(silent, null, Instant.now().minus(Duration.ofDays(9))); // old device that never pushed
        device(recent, Instant.now().minus(Duration.ofDays(1)));
        device(suspended, oldPush);
        device(partial, oldPush);
        device(partial, Instant.now().minus(Duration.ofHours(3))); // one device still syncs
        device(newDevice, oldPush);
        device(newDevice, null, Instant.now().minus(Duration.ofDays(1))); // registered yesterday, no push yet

        List<SilentTenant> rows = sources.silentTenants(Instant.now().minus(Duration.ofDays(5))).stream()
                .filter(s -> tenantIds.contains(s.tenantId())).toList();

        assertThat(rows).containsExactly(new SilentTenant(silentId, "Boutique Silencieuse", silent, oldPush));

        assertThat(notices.lastPushAtNotice(silentId)).isEmpty();
        notices.recordNotice(silentId, Instant.now(), oldPush);
        assertThat(notices.lastPushAtNotice(silentId)).contains(oldPush);
        Instant newer = oldPush.plus(Duration.ofDays(1));
        notices.recordNotice(silentId, Instant.now(), newer);
        assertThat(notices.lastPushAtNotice(silentId)).contains(newer);
        notices.deleteNotice(silentId);
        assertThat(notices.lastPushAtNotice(silentId)).isEmpty();
    }

    @Test
    void acks() {
        String key = "feedback:" + UUID.randomUUID();
        ackKeys.add(key);
        Instant first = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        acks.markHandled(key, first, UUID.randomUUID());
        acks.markHandled(key, Instant.now(), UUID.randomUUID()); // idempotent: first kept

        Map<String, Instant> handled = acks.handledAt(List.of(key, "health:database:2099-01-01-00"));
        assertThat(handled).containsOnlyKeys(key);
        assertThat(handled.get(key)).isEqualTo(first);
        assertThat(acks.handledAt(List.of())).isEmpty();

        assertThat(acks.delete(List.of(key, "health:never-acked"))).isEqualTo(1);
        assertThat(acks.handledAt(List.of(key))).isEmpty();

        String old = "health:uptime-" + UUID.randomUUID().toString().substring(0, 8);
        ackKeys.add(old);
        acks.markHandled(old, Instant.parse("1990-01-01T00:00:00Z"), UUID.randomUUID());
        assertThat(acks.purgeBefore(Instant.parse("1991-01-01T00:00:00Z"))).isGreaterThanOrEqualTo(1);
        assertThat(acks.handledAt(List.of(old))).isEmpty();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private HealthSample sample(Instant at, boolean ok, long requests, long errors, Integer p99) {
        sampleTimes.add(at);
        return new HealthSample(at, ok, requests, errors, p99 == null ? null : 10, p99 == null ? null : 50, p99, 2);
    }

    private String tenant(String schema, String name, String status, String plan) {
        schemas.add(schema);
        provisioner.provision("tenant-" + schema, schema);
        UUID id = UUID.randomUUID();
        tenantIds.add(id);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, name, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) VALUES (?, now(), now(), ?, ?, 1, 1, 1, ?, ?, ?)",
                id, schema.toUpperCase().replace('_', '-'), name, plan, schema, status);
        return schema;
    }

    private void subscription(String schema, String plan, String status, Instant expiresAt) {
        jdbc.update("DELETE FROM " + schema + ".subscriptions");
        jdbc.update("INSERT INTO " + schema + ".subscriptions (plan_type, status, expires_at) VALUES (?, ?, ?)",
                plan, status, Timestamp.from(expiresAt));
    }

    private void refusal(String schema, Instant at) {
        jdbc.update("INSERT INTO " + schema + ".sync_error_log (operation_id, operation_type, payload, created_at) "
                + "VALUES (?, 'CREATE_SALE', '{}'::jsonb, ?)", UUID.randomUUID().toString(), Timestamp.from(at));
    }

    private void report(String schema, String status, Instant at) {
        jdbc.update("INSERT INTO " + schema + ".reports (id, tenant_id, store_id, report_type, report_date, content, "
                + "delivery_status, last_attempt_at) VALUES (?, ?, ?, 'EOD', CURRENT_DATE, 'x', ?, ?)",
                UUID.randomUUID(), schema, UUID.randomUUID(), status, Timestamp.from(at));
    }

    private UUID feedback(String schema, String priority, Instant submittedAt, boolean handled) {
        UUID id = UUID.randomUUID();
        feedbackIds.add(id);
        jdbc.update("INSERT INTO public.feedback (id, type, description, tenant_id, user_id, submitted_at, priority, "
                + "handled_at) VALUES (?, 'BUG', 'Le scanner plante', ?, ?, ?, ?, ?)",
                id, schema, UUID.randomUUID(), Timestamp.from(submittedAt), priority,
                handled ? Timestamp.from(Instant.now()) : null);
        return id;
    }

    private void device(String schema, Instant lastPush) {
        device(schema, lastPush, Instant.now());
    }

    private void device(String schema, Instant lastPush, Instant updatedAt) {
        String deviceId = UUID.randomUUID().toString();
        devices.add(deviceId);
        jdbc.update("INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_push_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)",
                deviceId, UUID.randomUUID(), schema, lastPush == null ? null : Timestamp.from(lastPush),
                Timestamp.from(updatedAt));
    }
}
