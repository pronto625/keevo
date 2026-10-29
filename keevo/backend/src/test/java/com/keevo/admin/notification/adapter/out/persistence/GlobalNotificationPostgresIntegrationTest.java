package com.keevo.admin.notification.adapter.out.persistence;

import com.keevo.admin.notification.domain.model.Announcement;
import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason;
import com.keevo.admin.notification.domain.model.GlobalNotificationStatus;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.notification.domain.model.PushTenantRef;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 9-5 (2/2) — SQL of the global push table (V21) and of the push audience on a real PostgreSQL.
 * Commits (no transaction around the per-company loops): unique schemas / ids, no global count asserted
 * except on rows of this test.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({TenantSchemaProvisioner.class, TenantSchemaSyncService.class, JdbcGlobalNotificationRepository.class,
        JdbcPushAudienceAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Global push SQL on PostgreSQL (Story 9-5)")
class GlobalNotificationPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired JdbcGlobalNotificationRepository repository;
    @Autowired JdbcPushAudienceAdapter audience;
    @Autowired TenantSchemaSyncService schemaSync;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> notificationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        notificationIds.forEach(id -> jdbc.update("DELETE FROM public.global_notifications WHERE id = ?", id));
        tenantIds.forEach(id -> jdbc.update("DELETE FROM public.tenants WHERE id = ?", id));
        schemas.forEach(s -> jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE"));
    }

    // ── Audience ──────────────────────────────────────────────────────────────

    @Test
    void audience_filtersByPlanAndStatus_andReadsEveryToken() {
        PushTargetTenant premium = tenant("ACTIVE", "PREMIUM", "OWNER", "EMPLOYEE");
        PushTargetTenant free = tenant("ACTIVE", "FREE", "OWNER");
        PushTargetTenant trial = tenant("DELETION_PENDING", "PREMIUM_TRIAL", "OWNER");
        PushTargetTenant suspended = tenant("SUSPENDED", "PREMIUM", "OWNER");
        PushTargetTenant deleted = tenant("DELETED", "PREMIUM", "OWNER");

        List<UUID> premiumIds = ids(audience.targetTenants(GlobalNotificationTarget.PREMIUM, null));
        assertThat(premiumIds).contains(premium.id()).doesNotContain(free.id(), trial.id(), suspended.id(), deleted.id());
        assertThat(ids(audience.targetTenants(GlobalNotificationTarget.FREE, null)))
                .contains(free.id()).doesNotContain(premium.id(), trial.id());
        assertThat(ids(audience.targetTenants(GlobalNotificationTarget.TRIAL, null)))
                .contains(trial.id()).doesNotContain(premium.id(), free.id());
        assertThat(ids(audience.targetTenants(GlobalNotificationTarget.ALL, null)))
                .contains(premium.id(), free.id(), trial.id())
                .doesNotContain(suspended.id(), deleted.id(), UUID.fromString("00000000-0000-0000-0000-000000000000"));
        assertThat(ids(audience.targetTenants(GlobalNotificationTarget.TENANT, free.id()))).containsExactly(free.id());
        assertThat(audience.reachableTenant(suspended.id())).isEmpty();
        assertThat(audience.reachableTenant(premium.id())).contains(premium);

        // owners and employees
        assertThat(audience.deviceTokens(premium)).hasSize(2);
        assertThat(audience.countDeviceTokens(List.of(premium, free, trial))).isEqualTo(4);
    }

    @Test
    void audience_brokenSchema_skippedNotThrown() {
        PushTargetTenant ok = tenant("ACTIVE", "FREE", "OWNER");
        PushTargetTenant broken = tenant("ACTIVE", "FREE", "OWNER");
        jdbc.execute("DROP TABLE " + broken.schemaName() + ".device_tokens");

        assertThat(audience.deviceTokens(broken)).isEmpty();
        assertThat(audience.countDeviceTokens(List.of(broken, ok))).isEqualTo(1);
    }

    // ── Repository ────────────────────────────────────────────────────────────

    @Test
    void claimDue_onlyDueScheduledRows_once() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        GlobalNotification due = insert(now.minusSeconds(5), GlobalNotificationStatus.SCHEDULED, null);
        GlobalNotification later = insert(now.plus(1, ChronoUnit.HOURS), GlobalNotificationStatus.SCHEDULED, null);
        GlobalNotification cancelled = insert(now.minusSeconds(5), GlobalNotificationStatus.CANCELLED, null);

        List<UUID> claimed = ids2(repository.claimDue(now));
        assertThat(claimed).contains(due.id()).doesNotContain(later.id(), cancelled.id());
        assertThat(repository.findById(due.id()).orElseThrow().status()).isEqualTo(GlobalNotificationStatus.SENDING);
        assertThat(ids2(repository.claimDue(now))).doesNotContain(due.id());

        // the scheduled one is sent after its due time, not before
        assertThat(ids2(repository.claimDue(now.plus(1, ChronoUnit.HOURS)))).contains(later.id());
    }

    @Test
    void claimDue_concurrentClaims_neverReturnTheSameRowTwice() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<UUID> mine = new ArrayList<>();
        for (int i = 0; i < 20; i++) mine.add(insert(now.minusSeconds(1), GlobalNotificationStatus.SCHEDULED, null).id());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<GlobalNotification>>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) calls.add(() -> repository.claimDue(now));
            List<UUID> all = new ArrayList<>();
            for (Future<List<GlobalNotification>> f : pool.invokeAll(calls)) all.addAll(ids2(f.get()));
            List<UUID> claimedMine = all.stream().filter(mine::contains).toList();
            assertThat(claimedMine).hasSize(20).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancel_onlyWhileScheduled() {
        Instant now = Instant.now();
        GlobalNotification scheduled = insert(now.plusSeconds(3600), GlobalNotificationStatus.SCHEDULED, null);
        GlobalNotification sent = insert(now, GlobalNotificationStatus.SENT, null);

        assertThat(repository.cancelIfScheduled(scheduled.id(), now)).isTrue();
        GlobalNotification cancelled = repository.findById(scheduled.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(GlobalNotificationStatus.CANCELLED);
        assertThat(cancelled.sentAt()).isNotNull();
        assertThat(repository.claimDue(now.plusSeconds(7200)).stream().map(GlobalNotification::id))
                .doesNotContain(scheduled.id());
        assertThat(repository.cancelIfScheduled(sent.id(), now)).isFalse();
    }

    @Test
    void complete_andHistoryWithTenantName_andPurge() {
        PushTargetTenant t = tenant("ACTIVE", "FREE", "OWNER");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        GlobalNotification n = insert(now.minusSeconds(1), GlobalNotificationStatus.SCHEDULED, t.id());
        repository.claimDue(now);
        repository.complete(n.id(), true, null, 5, 4, 1, now);

        GlobalNotification row = repository.findById(n.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(GlobalNotificationStatus.SENT);
        assertThat(row.recipientCount()).isEqualTo(5);
        assertThat(row.deliveredCount()).isEqualTo(4);
        assertThat(row.failureCount()).isEqualTo(1);
        assertThat(row.sentAt()).isEqualTo(now);
        assertThat(row.targetTenantName()).startsWith("Boutique ");

        List<GlobalNotification> history = repository.findCreatedSince(now.minus(90, ChronoUnit.DAYS));
        assertThat(history.stream().map(GlobalNotification::id)).contains(n.id());

        GlobalNotification old = insertCreated(now.minus(91, ChronoUnit.DAYS), GlobalNotificationStatus.SENT);
        GlobalNotification oldSending = insertCreated(now.minus(91, ChronoUnit.DAYS), GlobalNotificationStatus.SENDING);
        GlobalNotification oldScheduled = insertCreated(now.minus(91, ChronoUnit.DAYS), GlobalNotificationStatus.SCHEDULED);
        assertThat(repository.findCreatedSince(now.minus(90, ChronoUnit.DAYS)).stream().map(GlobalNotification::id))
                .doesNotContain(old.id());
        repository.purgeCreatedBefore(now.minus(90, ChronoUnit.DAYS));
        assertThat(repository.findById(old.id())).isEmpty();
        assertThat(repository.findById(oldSending.id())).isEmpty();
        assertThat(repository.findById(oldScheduled.id())).isPresent();
        assertThat(repository.findById(n.id())).isPresent();
    }

    @Test
    void failStuck_onlySendingClaimedBeforeTheCutoff() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        GlobalNotification old = insert(now.minus(1, ChronoUnit.HOURS), GlobalNotificationStatus.SCHEDULED, null);
        repository.claimDue(now.minus(20, ChronoUnit.MINUTES)); // claimed 20 min ago
        GlobalNotification recent = insert(now.minus(2, ChronoUnit.MINUTES), GlobalNotificationStatus.SCHEDULED, null);
        repository.claimDue(now.minus(1, ChronoUnit.MINUTES)); // claimed 1 min ago (old one already SENDING)

        List<UUID> failed = ids2(repository.failStuck(now.minus(15, ChronoUnit.MINUTES), now));

        assertThat(failed).contains(old.id()).doesNotContain(recent.id());
        GlobalNotification row = repository.findById(old.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(GlobalNotificationStatus.FAILED);
        assertThat(row.failureReason()).isEqualTo(GlobalNotificationFailureReason.INTERRUPTED);
        assertThat(row.sentAt()).isEqualTo(now);
        assertThat(row.deliveredCount()).isZero();
        assertThat(repository.findById(recent.id()).orElseThrow().status()).isEqualTo(GlobalNotificationStatus.SENDING);
    }

    @Test
    void complete_failedStoresTheReason() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        GlobalNotification n = insert(now.minusSeconds(1), GlobalNotificationStatus.SCHEDULED, null);
        repository.claimDue(now);
        repository.complete(n.id(), false, GlobalNotificationFailureReason.NO_RECIPIENT, 0, 0, 0, now);
        GlobalNotification row = repository.findById(n.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(GlobalNotificationStatus.FAILED);
        assertThat(row.failureReason()).isEqualTo(GlobalNotificationFailureReason.NO_RECIPIENT);
        assertThat(row.sentAt()).isEqualTo(now);
    }

    @Test
    void sentAnnouncements_reachTheCompanyByTargetAndCurrentPlan() {
        PushTargetTenant free = tenant("ACTIVE", "FREE");
        PushTargetTenant premium = tenant("ACTIVE", "PREMIUM");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID all = sent(GlobalNotificationTarget.ALL, null, now);
        UUID toFree = sent(GlobalNotificationTarget.FREE, null, now);
        UUID toPremium = sent(GlobalNotificationTarget.PREMIUM, null, now);
        UUID toTrial = sent(GlobalNotificationTarget.TRIAL, null, now);
        UUID toFreeTenant = sent(GlobalNotificationTarget.TENANT, free.id(), now);
        UUID tooOld = sent(GlobalNotificationTarget.ALL, null, now.minus(2, ChronoUnit.DAYS));
        GlobalNotification scheduled = insert(now.minusSeconds(1), GlobalNotificationStatus.SCHEDULED, null);

        PushTenantRef freeRef = audience.tenantOfSchema(free.schemaName()).orElseThrow();
        assertThat(freeRef.planType()).isEqualTo("FREE");
        List<UUID> forFree = repository.findSentFor(freeRef, now.minus(1, ChronoUnit.DAYS)).stream()
                .map(Announcement::id).toList();
        assertThat(forFree).contains(all, toFree, toFreeTenant)
                .doesNotContain(toPremium, toTrial, tooOld, scheduled.id());

        List<UUID> forPremium = repository.findSentFor(audience.tenantOfSchema(premium.schemaName()).orElseThrow(),
                now.minus(1, ChronoUnit.DAYS)).stream().map(Announcement::id).toList();
        assertThat(forPremium).contains(all, toPremium).doesNotContain(toFree, toFreeTenant, toTrial);
        assertThat(audience.tenantOfSchema("public")).isEmpty();
    }

    @Test
    void searchReachable_byName_onlyReachableCompanies() {
        PushTargetTenant active = tenant("ACTIVE", "FREE");
        PushTargetTenant suspended = tenant("SUSPENDED", "FREE");
        String term = active.schemaName().substring(3); // unique part of « Boutique kv_xxxxxx »
        assertThat(audience.searchReachable(term.toUpperCase(), 20)).extracting(PushTenantRef::id)
                .containsExactly(active.id());
        assertThat(audience.searchReachable(suspended.schemaName().substring(3), 20)).isEmpty();
        assertThat(audience.searchReachable("%", 20)).extracting(PushTenantRef::id).doesNotContain(active.id());
    }

    @Test
    void countDeviceTokens_sameTokenInTwoCompanies_countsOnce() {
        PushTargetTenant a = tenant("ACTIVE", "FREE", "OWNER");
        PushTargetTenant b = tenant("ACTIVE", "FREE");
        String shared = audience.deviceTokens(a).get(0);
        jdbc.update("INSERT INTO " + b.schemaName() + ".device_tokens (id, user_id, token, platform, role) "
                + "VALUES (?, ?, ?, 'ANDROID', 'EMPLOYEE')", UUID.randomUUID(), UUID.randomUUID(), shared);
        assertThat(audience.countDeviceTokens(List.of(a, b))).isEqualTo(1);
    }

    @Test
    void globalOnlyTables_neverMirroredIntoATenantSchema() throws Exception {
        PushTargetTenant t = tenant("ACTIVE", "FREE");
        schemaSync.syncNow(t.schemaName());
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class,
                t.schemaName());
        assertThat(tables).isNotEmpty().doesNotContainAnyElementsOf(TenantSchemaSyncService.globalOnlyTables());
        // explicit names: a table dropped from the global-only list by mistake would still be caught here
        assertThat(tables).doesNotContain("tenants", "tenant_payments", "tenant_plan_events", "plan_expiry_reminders",
                "user_daily_activity", "platform_health_samples", "admin_alert_acks", "sync_silence_notices",
                "global_notifications", "module_definitions", "tenant_modules");
    }

    @Test
    void tenantTargetRequiresTenantId() {
        UUID id = UUID.randomUUID();
        notificationIds.add(id);
        assertThatThrownBy(() -> repository.insert(new GlobalNotification(id, "T", "B", GlobalNotificationTarget.TENANT,
                null, null, Instant.now(), GlobalNotificationStatus.SCHEDULED, null, null, null, null, UUID.randomUUID(),
                Instant.now(), null))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private GlobalNotification insert(Instant scheduledAt, GlobalNotificationStatus status, UUID tenantId) {
        UUID id = UUID.randomUUID();
        notificationIds.add(id);
        GlobalNotification n = new GlobalNotification(id, "Maintenance", "Ce soir",
                tenantId == null ? GlobalNotificationTarget.ALL : GlobalNotificationTarget.TENANT, tenantId, null,
                scheduledAt, status, null, null, null, null, UUID.randomUUID(), Instant.now(), null);
        repository.insert(n);
        return n;
    }

    private UUID sent(GlobalNotificationTarget target, UUID tenantId, Instant sentAt) {
        GlobalNotification n = insert(sentAt.minusSeconds(1), GlobalNotificationStatus.SCHEDULED, tenantId);
        if (target != GlobalNotificationTarget.TENANT) {
            jdbc.update("UPDATE public.global_notifications SET target = ? WHERE id = ?", target.name(), n.id());
        }
        jdbc.update("UPDATE public.global_notifications SET status = 'SENT', sent_at = ? WHERE id = ?",
                java.sql.Timestamp.from(sentAt), n.id());
        return n.id();
    }

    private GlobalNotification insertCreated(Instant createdAt, GlobalNotificationStatus status) {
        UUID id = UUID.randomUUID();
        notificationIds.add(id);
        GlobalNotification n = new GlobalNotification(id, "Ancienne", "Annonce", GlobalNotificationTarget.ALL, null,
                null, createdAt, status, 0, 0, 0, null, UUID.randomUUID(), createdAt, createdAt);
        repository.insert(n);
        return n;
    }

    private PushTargetTenant tenant(String status, String plan, String... tokenRoles) {
        String schema = "kv_" + randomSuffix();
        schemas.add(schema);
        provisioner.provision("tenant-" + schema, schema);
        UUID id = UUID.randomUUID();
        tenantIds.add(id);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, name, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) VALUES (?, now(), now(), ?, ?, 1, 1, 1, ?, ?, ?)",
                id, schema.toUpperCase().replace('_', '-'), "Boutique " + schema, plan, schema, status);
        for (String role : tokenRoles) {
            jdbc.update("INSERT INTO " + schema + ".device_tokens (id, user_id, token, platform, role) "
                    + "VALUES (?, ?, ?, 'ANDROID', ?)", UUID.randomUUID(), UUID.randomUUID(),
                    "tok-" + UUID.randomUUID(), role);
        }
        return new PushTargetTenant(id, schema);
    }

    private static String randomSuffix() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        return sb.toString();
    }

    private static List<UUID> ids(List<PushTargetTenant> tenants) {
        return tenants.stream().map(PushTargetTenant::id).toList();
    }

    private static List<UUID> ids2(List<GlobalNotification> rows) {
        return rows.stream().map(GlobalNotification::id).toList();
    }
}
