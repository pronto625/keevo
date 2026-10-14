package com.keevo.admin.subscription.application.service;

import com.keevo.admin.subscription.adapter.out.persistence.JdbcTenantPaymentRepository;
import com.keevo.admin.subscription.domain.model.PaymentMethod;
import com.keevo.admin.subscription.domain.model.TenantPayment;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumCommand;
import com.keevo.admin.subscription.domain.port.in.DowngradeToFreeCommand;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantCommand;
import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.infrastructure.scheduling.PlanTypeRealignJob;
import com.keevo.subscription.plan.adapter.out.persistence.impl.JdbcPlanChangeRecorder;
import com.keevo.subscription.plan.application.service.ChangeTenantPlanService;
import com.keevo.subscription.plan.application.service.PlanExpiryReminderService;
import com.keevo.subscription.plan.application.service.SubscriptionExpiryService;
import com.keevo.subscription.plan.domain.model.event.PlanExpiredEvent;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsCommand;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 9-2 — V17 tables and the plan management flow on a real PostgreSQL: check constraints,
 * activation (payment + subscription + public plan + history), downgrade, suspension, expiry job
 * (event + history), idempotent reminders and the public plan realignment job.
 *
 * <p>Commits tenant rows and schemas, removed in {@link #cleanUp()}.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(TenantSchemaProvisioner.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Plan management on PostgreSQL (Story 9-2)")
class TenantPlanManagementPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.systemUTC();

    @AfterEach
    void cleanUp() {
        tenantIds.forEach(id -> {
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.tenant_plan_events WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", id);
        });
        userIds.forEach(id -> jdbc.update("DELETE FROM public.users WHERE id = ?", id));
        schemas.forEach(s -> jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE"));
    }

    // ── Migration ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V17 check constraints: amount > 0, known method, period_end ≥ period_start, known cause, unique reminder")
    void checkConstraints() {
        UUID t = tenant("kv_pm9201", "KV-PM9201", "PREMIUM_TRIAL");
        UUID actor = UUID.randomUUID();
        String insert = "INSERT INTO public.tenant_payments (tenant_id, amount_xaf, method, paid_at, period_start, "
                + "period_end, recorded_by) VALUES (?, ?, ?, now(), ?, ?, ?)";
        LocalDate d = LocalDate.of(2026, 10, 13);

        jdbc.update(insert, t, 10_000, "MOBILE_MONEY", d, d.plusDays(30), actor);
        assertThatThrownBy(() -> jdbc.update(insert, t, 0, "CASH", d, d, actor))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, t, 100, "BITCOIN", d, d, actor))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, t, 100, "CASH", d, d.minusDays(1), actor))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO public.tenant_plan_events (tenant_id, to_plan, cause) VALUES (?, 'FREE', 'WHATEVER')", t))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO public.plan_expiry_reminders (tenant_id, threshold_days, expiry_date) VALUES (?, 7, ?)", t, d);
        assertThat(jdbc.update("INSERT INTO public.plan_expiry_reminders (tenant_id, threshold_days, expiry_date) "
                + "VALUES (?, 7, ?) ON CONFLICT DO NOTHING", t, d)).isZero();
    }

    // ── Activation / downgrade ────────────────────────────────────────────────

    @Test
    @DisplayName("activate Premium: payment row, schema subscription PREMIUM, public plan_type synced, ADMIN_ACTIVATION event; then downgrade")
    void activateThenDowngrade() {
        UUID t = tenant("kv_pm9202", "KV-PM9202", "PREMIUM_TRIAL");
        UUID actor = UUID.randomUUID();
        AdminPlanService service = planService();
        LocalDate today = LocalDate.now(clock.withZone(com.keevo.shared.domain.time.KeevoZones.WAT));

        service.activatePremium(new ActivatePremiumCommand(actor, t, today.plusDays(30), 10_000,
                PaymentMethod.MOBILE_MONEY, null, "OM"));

        List<TenantPayment> payments = new JdbcTenantPaymentRepository(jdbc).findByTenant(t);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).periodStart()).isEqualTo(today);
        assertThat(payments.get(0).periodEnd()).isEqualTo(today.plusDays(30));
        Map<String, Object> sub = jdbc.queryForMap(
                "SELECT plan_type, status, max_stores, expires_at FROM \"kv_pm9202\".subscriptions");
        assertThat(sub.get("plan_type")).isEqualTo("PREMIUM");
        assertThat(sub.get("status")).isEqualTo("ACTIVE");
        assertThat(sub.get("max_stores")).isEqualTo(Integer.MAX_VALUE);
        assertThat(((java.sql.Timestamp) sub.get("expires_at")).toInstant())
                .isEqualTo(AdminPlanService.endOfDayWat(today.plusDays(30)));
        assertThat(publicPlan(t)).isEqualTo("PREMIUM");
        assertThat(events(t)).containsExactly("PREMIUM_TRIAL>PREMIUM:ADMIN_ACTIVATION");

        service.downgradeToFree(new DowngradeToFreeCommand(actor, t, "non renouvelé"));

        assertThat(jdbc.queryForObject("SELECT plan_type FROM \"kv_pm9202\".subscriptions", String.class)).isEqualTo("FREE");
        assertThat(jdbc.queryForObject("SELECT max_products FROM \"kv_pm9202\".subscriptions", Integer.class)).isEqualTo(500);
        assertThat(publicPlan(t)).isEqualTo("FREE");
        assertThat(events(t)).containsExactly("PREMIUM_TRIAL>PREMIUM:ADMIN_ACTIVATION", "PREMIUM>FREE:ADMIN_DOWNGRADE");
    }

    // ── Suspension ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("suspend ACTIVE → SUSPENDED + every member revoked; suspend again → INVALID_STATE; reactivate → ACTIVE")
    void suspendReactivate() {
        UUID t = tenant("kv_pm9203", "KV-PM9203", "FREE");
        UUID owner = member(t, "OWNER");
        UUID employee = member(t, "EMPLOYEE");
        TokenRevocationPort revocation = mock(TokenRevocationPort.class);
        AdminTenantSuspensionService service = new AdminTenantSuspensionService(
                new TargetTenantLookup(jdbc), jdbc, revocation, publisher, clock);

        service.suspend(new SuspendTenantCommand(UUID.randomUUID(), t, "fraude"));

        assertThat(status(t)).isEqualTo("SUSPENDED");
        verify(revocation).revokeAllSessions(owner, "kv_pm9203");
        verify(revocation).revokeAllSessions(employee, "kv_pm9203");
        assertThatThrownBy(() -> service.suspend(new SuspendTenantCommand(UUID.randomUUID(), t, "fraude")))
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("INVALID_STATE");

        service.reactivate(UUID.randomUUID(), t);
        assertThat(status(t)).isEqualTo("ACTIVE");

        jdbc.update("UPDATE public.tenants SET status = 'DELETION_PENDING' WHERE id = ?", t);
        assertThatThrownBy(() -> service.suspend(new SuspendTenantCommand(UUID.randomUUID(), t, "fraude")))
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("INVALID_STATE");
    }

    // ── Expiry job ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("expiry job: expired PREMIUM → FREE, public plan synced, EXPIRY event, PlanExpiredEvent published")
    void expiryJob() {
        UUID t = tenant("kv_pm9204", "KV-PM9204", "PREMIUM");
        jdbc.update("UPDATE \"kv_pm9204\".subscriptions SET plan_type = 'PREMIUM', expires_at = now() - interval '1 hour'");
        SubscriptionExpiryService service = new SubscriptionExpiryService(jdbc, new JdbcPlanChangeRecorder(jdbc),
                publisher, transactionManager, clock);

        service.execute(new DowngradeExpiredTrialsCommand("test"));

        assertThat(jdbc.queryForObject("SELECT plan_type FROM \"kv_pm9204\".subscriptions", String.class)).isEqualTo("FREE");
        assertThat(publicPlan(t)).isEqualTo("FREE");
        assertThat(events(t)).containsExactly("PREMIUM>FREE:EXPIRY");
        verify(publisher).publishEvent(argThatExpired(t));
    }

    // ── Reminders ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reminder 7 days before a PREMIUM expiry: sent once, the second run sends nothing")
    void reminderIdempotent() {
        UUID t = tenant("kv_pm9205", "KV-PM9205", "PREMIUM");
        LocalDate today = LocalDate.now(clock.withZone(com.keevo.shared.domain.time.KeevoZones.WAT));
        jdbc.update("UPDATE \"kv_pm9205\".subscriptions SET plan_type = 'PREMIUM', expires_at = ?",
                java.sql.Timestamp.from(AdminPlanService.endOfDayWat(today.plusDays(7))));
        WhatsAppPort whatsApp = mock(WhatsAppPort.class);
        OwnerRecipientResolver resolver = mock(OwnerRecipientResolver.class);
        when(resolver.resolveOwnerWhatsAppPhone("kv_pm9205")).thenReturn(Optional.of("+237690009205"));
        PlanExpiryReminderService service = new PlanExpiryReminderService(jdbc, whatsApp, resolver, clock);

        service.execute();
        service.execute();

        verify(whatsApp, times(1)).sendReport(eq("+237690009205"), anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.plan_expiry_reminders WHERE tenant_id = ?",
                Integer.class, t)).isEqualTo(1);
    }

    // ── Realign job ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("realign job: stale public plan_type PREMIUM_TRIAL realigned on the schema's FREE; idempotent")
    void realign() {
        UUID t = tenant("kv_pm9206", "KV-PM9206", "PREMIUM_TRIAL");
        jdbc.update("UPDATE \"kv_pm9206\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        PlanTypeRealignJob job = new PlanTypeRealignJob(jdbc, mock(LockProvider.class), false);

        assertThat(job.realignAll()).isGreaterThanOrEqualTo(1);
        assertThat(publicPlan(t)).isEqualTo("FREE");
        job.realignAll();
        assertThat(publicPlan(t)).isEqualTo("FREE");
        // one REALIGNMENT event from the stale public value to the schema value, not repeated
        assertThat(events(t)).containsExactly("PREMIUM_TRIAL>FREE:REALIGNMENT");
    }

    // ── Payments: order, void ─────────────────────────────────────────────────

    @Test
    @DisplayName("payments: two rows with different paid_at → newest first; void once → voided, twice → false")
    void paymentsNewestFirst_andVoid() {
        UUID t = tenant("kv_pm9241", "KV-PM9241", "PREMIUM");
        JdbcTenantPaymentRepository repo = new JdbcTenantPaymentRepository(jdbc);
        UUID actor = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        LocalDate d = LocalDate.of(2026, 10, 1);
        // inserted newest first then oldest, so the order cannot come from the insertion order
        repo.insert(new TenantPayment(newer, t, 20_000, PaymentMethod.CASH, Instant.parse("2026-10-05T09:00:00Z"),
                d, d.plusDays(30), null, actor));
        repo.insert(new TenantPayment(older, t, 10_000, PaymentMethod.MOBILE_MONEY, Instant.parse("2026-09-01T09:00:00Z"),
                d.minusDays(30), d, null, actor));

        assertThat(repo.findByTenant(t)).extracting(TenantPayment::id).containsExactly(newer, older);

        Instant at = Instant.parse("2026-10-06T09:00:00Z");
        assertThat(repo.voidPayment(t, newer, actor, "Erreur de saisie", at)).isTrue();
        assertThat(repo.voidPayment(t, newer, actor, "Encore", at)).isFalse();
        assertThat(repo.voidPayment(UUID.randomUUID(), older, actor, "Autre entreprise", at)).isFalse();
        TenantPayment voided = repo.findById(t, newer).orElseThrow();
        assertThat(voided.voidedAt()).isEqualTo(at);
        assertThat(voided.voidReason()).isEqualTo("Erreur de saisie");
        assertThat(repo.findById(t, older).orElseThrow().isVoided()).isFalse();
    }

    @Test
    @DisplayName("suspension trace: date, reason and actor stored on suspend, cleared on reactivate")
    void suspensionTrace() {
        UUID t = tenant("kv_pm9242", "KV-PM9242", "FREE");
        UUID actor = UUID.randomUUID();
        AdminTenantSuspensionService service = new AdminTenantSuspensionService(
                new TargetTenantLookup(jdbc), jdbc, mock(TokenRevocationPort.class), publisher, clock);

        service.suspend(new SuspendTenantCommand(actor, t, " impayés "));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT suspended_at, suspended_reason, suspended_by FROM public.tenants WHERE id = ?", t);
        assertThat(row.get("suspended_at")).isNotNull();
        assertThat(row.get("suspended_reason")).isEqualTo("impayés");
        assertThat(row.get("suspended_by")).isEqualTo(actor);

        service.reactivate(actor, t);

        row = jdbc.queryForMap(
                "SELECT suspended_at, suspended_reason, suspended_by FROM public.tenants WHERE id = ?", t);
        assertThat(row.get("suspended_at")).isNull();
        assertThat(row.get("suspended_reason")).isNull();
        assertThat(row.get("suspended_by")).isNull();
    }

    @Test
    @DisplayName("V22: REALIGNMENT accepted by the cause check")
    void realignmentCauseAccepted() {
        UUID t = tenant("kv_pm9243", "KV-PM9243", "FREE");
        assertThat(jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause) "
                + "VALUES (?, 'PREMIUM_TRIAL', 'FREE', 'REALIGNMENT')", t)).isEqualTo(1);
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("alerts: +7 d listed, +8 d / past / non-ACTIVE not; EXPIRY 3 d old listed, 10 d old or re-activated not")
    void listAlerts() {
        LocalDate today = LocalDate.now(clock.withZone(com.keevo.shared.domain.time.KeevoZones.WAT));
        UUID in7 = premiumExpiring("kv_pm9211", "KV-PM9211", today.plusDays(7), "ACTIVE");
        UUID in8 = premiumExpiring("kv_pm9212", "KV-PM9212", today.plusDays(8), "ACTIVE");
        UUID past = premiumExpiring("kv_pm9213", "KV-PM9213", today.minusDays(1), "ACTIVE");
        UUID suspended = premiumExpiring("kv_pm9214", "KV-PM9214", today.plusDays(3), "SUSPENDED");
        UUID expired3 = tenant("kv_pm9215", "KV-PM9215", "FREE");
        UUID expired10 = tenant("kv_pm9216", "KV-PM9216", "FREE");
        UUID reactivated = tenant("kv_pm9217", "KV-PM9217", "PREMIUM");
        planEvent(expired3, "PREMIUM", "FREE", "EXPIRY", "3 days");
        planEvent(expired10, "PREMIUM", "FREE", "EXPIRY", "10 days");
        planEvent(reactivated, "PREMIUM", "FREE", "EXPIRY", "3 days");
        planEvent(reactivated, "FREE", "PREMIUM", "ADMIN_ACTIVATION", "1 day");

        List<com.keevo.admin.subscription.domain.model.PlanAlert> alerts = planService().listAlerts().stream()
                .filter(a -> tenantIds.contains(a.tenantId())).toList();

        assertThat(alerts).extracting(a -> a.tenantId() + ":" + a.kind())
                .containsExactlyInAnyOrder(in7 + ":EXPIRING", expired3 + ":EXPIRED");
        var expiring = alerts.stream().filter(a -> a.tenantId().equals(in7)).findFirst().orElseThrow();
        assertThat(expiring.daysLeft()).isEqualTo(7);
        assertThat(expiring.tenantName()).isEqualTo("Boutique KV-PM9211");
        var expired = alerts.stream().filter(a -> a.tenantId().equals(expired3)).findFirst().orElseThrow();
        assertThat(expired.tenantName()).isEqualTo("Boutique KV-PM9215");
        assertThat(expired.plan()).isEqualTo("PREMIUM");
        assertThat(alerts).extracting(a -> a.tenantId())
                .doesNotContain(in8, past, suspended, expired10, reactivated);
    }

    // ── Admin tenant list / detail ────────────────────────────────────────────

    @Test
    @DisplayName("tenant list returns the raw plan code; detail returns the schema subscription")
    void listAndDetailPlan() {
        UUID t = tenant("kv_pm9221", "KV-PM9221", "PREMIUM_TRIAL");
        Instant expires = AdminPlanService.endOfDayWat(LocalDate.of(2027, 4, 1));
        jdbc.update("UPDATE \"kv_pm9221\".subscriptions SET expires_at = ?", java.sql.Timestamp.from(expires));
        var tenantService = new com.keevo.admin.tenant.application.service.AdminTenantService(
                jdbc, mock(com.keevo.shared.infrastructure.scheduling.AccountDeletionScheduler.class));

        var page = tenantService.execute(new com.keevo.admin.tenant.domain.port.in.ListTenantsQuery(
                "Boutique KV-PM9221", null, null, null, null, null, null, 0, 25));
        assertThat(page.getContent()).extracting(com.keevo.admin.tenant.domain.model.AdminTenantListItem::plan)
                .containsExactly("PREMIUM_TRIAL");

        var detail = tenantService.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(t, 0));
        assertThat(detail.plan().planType()).isEqualTo("PREMIUM_TRIAL");
        assertThat(detail.plan().expiresAt()).isEqualTo(expires);
    }

    // ── V17 backfill ──────────────────────────────────────────────────────────

    /** Copy of the V17 backfill statement. */
    private static final String BACKFILL_SQL =
            "INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at) "
            + "SELECT t.id, NULL, 'PREMIUM_TRIAL', 'REGISTRATION', t.created_at "
            + "FROM public.tenants t "
            + "WHERE t.id <> '00000000-0000-0000-0000-000000000000'::uuid "
            + "  AND NOT EXISTS ( "
            + "      SELECT 1 FROM public.tenant_plan_events e "
            + "      WHERE e.tenant_id = t.id AND e.cause = 'REGISTRATION')";

    @Test
    @DisplayName("V17 backfill: one REGISTRATION row per real tenant at created_at, none for the system tenant, idempotent")
    void backfill() {
        UUID system = UUID.fromString("00000000-0000-0000-0000-000000000000");
        boolean insertedSystem = jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, name, "
                + "max_employees, max_products, max_stores, plan_type, schema_name, status) "
                + "VALUES (?, now(), now(), 'KV-ADMIN', 'Keevo', 1, 1, 1, 'PREMIUM', 'public', 'ACTIVE') "
                + "ON CONFLICT DO NOTHING", system) == 1;
        try {
            UUID a = tenant("kv_pm9231", "KV-PM9231", "FREE");
            UUID b = tenant("kv_pm9232", "KV-PM9232", "PREMIUM");
            java.sql.Timestamp createdA = java.sql.Timestamp.from(Instant.parse("2026-03-01T10:00:00Z"));
            java.sql.Timestamp createdB = java.sql.Timestamp.from(Instant.parse("2026-05-15T08:30:00Z"));
            jdbc.update("UPDATE public.tenants SET created_at = ? WHERE id = ?", createdA, a);
            jdbc.update("UPDATE public.tenants SET created_at = ? WHERE id = ?", createdB, b);

            jdbc.update(BACKFILL_SQL);

            for (var e : Map.of(a, createdA, b, createdB).entrySet()) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT from_plan, to_plan, occurred_at FROM public.tenant_plan_events "
                        + "WHERE tenant_id = ? AND cause = 'REGISTRATION'", e.getKey());
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0).get("from_plan")).isNull();
                assertThat(rows.get(0).get("to_plan")).isEqualTo("PREMIUM_TRIAL");
                assertThat(((java.sql.Timestamp) rows.get(0).get("occurred_at")).toInstant())
                        .isEqualTo(e.getValue().toInstant());
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_plan_events WHERE tenant_id = ?",
                    Integer.class, system)).isZero();
            assertThat(jdbc.update(BACKFILL_SQL)).isZero();
        } finally {
            if (insertedSystem) jdbc.update("DELETE FROM public.tenants WHERE id = ?", system);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID premiumExpiring(String schema, String code, LocalDate expiry, String subStatus) {
        UUID id = tenant(schema, code, "PREMIUM");
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'PREMIUM', status = ?, expires_at = ?",
                subStatus, java.sql.Timestamp.from(AdminPlanService.endOfDayWat(expiry)));
        return id;
    }

    private void planEvent(UUID tenantId, String from, String to, String cause, String age) {
        jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at) "
                + "VALUES (?, ?, ?, ?, now() - ?::interval)", tenantId, from, to, cause, age);
    }

    private AdminPlanService planService() {
        JdbcPlanChangeRecorder recorder = new JdbcPlanChangeRecorder(jdbc);
        return new AdminPlanService(new TargetTenantLookup(jdbc), new ChangeTenantPlanService(jdbc, recorder),
                new JdbcTenantPaymentRepository(jdbc), publisher, jdbc, clock);
    }

    private UUID tenant(String schema, String code, String publicPlan) {
        schemas.add(schema);
        provisioner.provision("tenant-" + schema, schema); // seeds a PREMIUM_TRIAL subscription
        UUID id = UUID.randomUUID();
        tenantIds.add(id);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, name, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) "
                + "VALUES (?, now(), now(), ?, ?, 1, 1, 1, ?, ?, 'ACTIVE')", id, code, "Boutique " + code, publicPlan, schema);
        return id;
    }

    private UUID member(UUID tenantId, String role) {
        UUID userId = UUID.randomUUID();
        String phone = "+2376" + String.format("%08d", Math.abs(userId.hashCode()) % 100_000_000);
        jdbc.update("INSERT INTO public.users (id, phone_number, password_hash, role, is_active, failed_attempts, "
                + "created_at, updated_at) VALUES (?, ?, 'x', ?, true, 0, now(), now())", userId, phone, role);
        userIds.add(userId);
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, user_id, tenant_id, role, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, true, now(), now())", UUID.randomUUID(), userId, tenantId, role);
        return userId;
    }

    private String publicPlan(UUID id) {
        return jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, id);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM public.tenants WHERE id = ?", String.class, id);
    }

    private List<String> events(UUID id) {
        return jdbc.queryForList("SELECT COALESCE(from_plan, '') || '>' || to_plan || ':' || cause "
                + "FROM public.tenant_plan_events WHERE tenant_id = ? ORDER BY occurred_at, ctid", String.class, id);
    }

    private static Object argThatExpired(UUID tenantId) {
        return org.mockito.ArgumentMatchers.<Object>argThat(e -> e instanceof PlanExpiredEvent p && p.tenantId().equals(tenantId));
    }
}
