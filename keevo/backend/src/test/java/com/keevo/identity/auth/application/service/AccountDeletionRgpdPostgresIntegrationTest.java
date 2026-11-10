package com.keevo.identity.auth.application.service;

import com.keevo.admin.tenant.application.service.AdminTenantService;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.AccountDeletionLogRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.port.out.TenantFilesPurger;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 16.6 (FR91, RGPD) on a real PostgreSQL 16: the account deletion leaves no personal data of
 * the tenant's (non-shared) users in any {@code public} table, purges the export files, and leaves a
 * durable, non-personal trace in {@code public.account_deletion_log} (REQUESTED → EXECUTED, cancels,
 * admin force-delete). One test per row of the spec's I/O matrix.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Account deletion — RGPD purge and deletion trace (Story 16.6)")
class AccountDeletionRgpdPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired TenantFactory tenantFactory;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired TenantSchemaProvisioner schemaProvisioner;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountDeletionService accountDeletionService;
    @Autowired AdminTenantService adminTenantService;
    @Autowired AccountDeletionLogRepository deletionLog;
    @Autowired List<TenantFilesPurger> filePurgers;

    @Value("${keevo.export.storage-dir}")
    String exportStorageDir;

    private final WhatsAppSender whatsAppSender = mock(WhatsAppSender.class);

    private TenantDeletionService scheduler(JdbcTemplate jdbcTemplate) {
        return new TenantDeletionService(tenantRepository, userRepository, whatsAppSender, schemaProvisioner,
                jdbcTemplate, transactionManager, Clock.systemUTC(), filePurgers, deletionLog);
    }

    // ── Seeding ───────────────────────────────────────────────────────────────────────────────

    private record Seed(UUID tenantId, String schema, String code, UUID ownerId, UUID employeeId,
                        String employeePhone, Path exportDir) {}

    private static String randomPhone() {
        return "+2439" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private UUID insertUser(String phone, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, ?)",
                id, phone, role);
        return id;
    }

    private void insertMembership(UUID tenantId, UUID userId, String role) {
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), true, ?, ?, ?)",
                UUID.randomUUID(), role, tenantId, userId);
    }

    private void insertResetToken(UUID userId, String phone) {
        jdbc.update("INSERT INTO public.password_reset_tokens (id, user_id, phone_number, code_hash, expires_at, "
                + "attempts, created_at, updated_at) VALUES (?, ?, ?, 'hash', now() + interval '10 minutes', 0, "
                + "now() - interval '1 hour', now())", UUID.randomUUID(), userId, phone);
    }

    /** The tenant's own public rows, keyed as production writes them (schema name or tenant uuid). */
    private void insertTenantPublicRows(UUID tenantId, String schema, UUID userId) {
        // Story 20.7 — an old Bref link of an imported invoice of the tenant
        jdbc.update("INSERT INTO public.invoice_legacy_links (token, tenant_schema, invoice_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), schema, UUID.randomUUID());
        jdbc.update("INSERT INTO public.feedback (type, description, tenant_id, user_id, submitted_at) "
                + "VALUES ('BUG', 'Je suis Jean, mon numéro est le 699 00 00 00', ?, ?, now())", schema, userId);
        jdbc.update("INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) VALUES (?, ?, ?)",
                userId, tenantId, LocalDate.now());
        jdbc.update("INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_push_at, updated_at) "
                + "VALUES (?, ?, ?, now(), now())", UUID.randomUUID().toString(), userId, schema);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, disabled_at) "
                + "VALUES (?, 'rgpd-test', 'DISABLED', 'STANDARD', now() - interval '400 days') "
                + "ON CONFLICT (tenant_id, module_id) DO NOTHING", tenantId);
        jdbc.update("INSERT INTO public.plan_expiry_reminders (tenant_id, threshold_days, expiry_date) "
                + "VALUES (?, 7, ?)", tenantId, LocalDate.now().plusDays(7));
        jdbc.update("INSERT INTO public.sync_silence_notices (tenant_id, notified_at, last_push_at_at_notice) "
                + "VALUES (?, now(), now() - interval '6 days')", tenantId);
        // Story 19.8 — usage counters and packs
        jdbc.update("INSERT INTO public.usage_counters (tenant_id, metric, period, period_key, used) "
                + "VALUES (?, 'DOCUMENTS', 'MONTH', '2026-11', 3)", tenantId);
        jdbc.update("INSERT INTO public.usage_credits (tenant_id, metric, remaining) VALUES (?, 'SMS', 10)", tenantId);
        // Story 21.3 — account credit, its journal (support reason) and the pack journal (actor)
        jdbc.update("INSERT INTO public.tenant_credits (tenant_id, balance_xaf) VALUES (?, 500)", tenantId);
        jdbc.update("INSERT INTO public.tenant_credit_movements (id, tenant_id, amount_xaf, balance_after_xaf, kind, "
                + "actor_id, reason) VALUES (gen_random_uuid(), ?, 500, 500, 'ADMIN_ADJUSTMENT', gen_random_uuid(), "
                + "'Geste commercial')", tenantId);
        jdbc.update("INSERT INTO public.usage_credit_movements (id, tenant_id, metric, delta, remaining_after, source, "
                + "actor_id) VALUES (gen_random_uuid(), ?, 'SMS', 10, 10, 'MIGRATION', gen_random_uuid())", tenantId);
        insertLaterPublicRows(tenantId, schema, userId);
    }

    private static String digits(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            s.append(ThreadLocalRandom.current().nextInt(10));
        }
        return s.toString();
    }

    /**
     * 16.6 reprise (2026-11-10) — the tables added after V40 (19.9b–21.10): WhatsApp Business account (21.5), MCP token
     * (21.9), tool-call journal (21.8), module activation (19.13), company discount + its catalogue journal + a payment
     * request using it with the payer's phone (21.10 / 21.1), Expo ticket and browser push subscription (19.9b).
     */
    private void insertLaterPublicRows(UUID tenantId, String schema, UUID userId) {
        jdbc.update("INSERT INTO public.whatsapp_accounts (tenant_id, waba_id, phone_number_id, display_phone_number, "
                + "verified_name, access_token_enc, connected_by) VALUES (?, ?, ?, '+237 699 00 00 00', 'Ets Jean', "
                + "decode(md5(random()::text) || md5(random()::text) || md5(random()::text), 'hex'), ?)", tenantId, digits(15), digits(15), userId);
        jdbc.update("INSERT INTO public.mcp_access_tokens (id, tenant_id, user_id, name, token_hash, hint, tools, "
                + "expires_at) VALUES (gen_random_uuid(), ?, ?, 'Claude de Jean', decode(md5(random()::text) || md5(random()::text), 'hex'), 'abcd', "
                + "ARRAY['sales_summary'], now() + interval '30 days')", tenantId, userId);
        jdbc.update("INSERT INTO public.ai_tool_calls (tenant_id, actor_id, tool, channel, outcome, duration_ms) "
                + "VALUES (?, ?, 'sales_summary', 'ASSISTANT', 'OK', 12)", tenantId, userId);
        jdbc.update("INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_by) "
                + "VALUES (?, 'rgpd-test', true, ?) ON CONFLICT (tenant_id, module_id) DO NOTHING", tenantId, userId);
        UUID discountId = jdbc.queryForObject("INSERT INTO public.discounts (scope, label, percent_off, tenant_id) "
                + "VALUES ('TENANT', ?, 10, ?) RETURNING id", UUID.class, "Remise de Jean, " + schema, tenantId);
        jdbc.update("INSERT INTO public.offer_change_log (entity, entity_key, summary) VALUES ('DISCOUNT', ?, ?)",
                discountId.toString(), "Remise « Remise de Jean, " + schema + " » créée, pour « Ets Jean " + schema + " »");
        jdbc.update("INSERT INTO public.payment_requests (id, tenant_id, tenant_schema, requested_by, provider, method, "
                + "phone, plan_code, cycle_code, plan_label, cycle_label, months, base_xaf, reduction_xaf, "
                + "amount_due_xaf, discount_id, discount_label, priced_at, status, next_step, app_transaction_ref, "
                + "failure_reason) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, 'SANDBOX', 'MTN_MOMO', '+237670000000', 'FREE', 'MONTHLY', "
                + "'Gratuit', 'Mensuel', 1, 5000, 500, 4500, ?, ?, now(), 'FAILED', 'NONE', ?, 'Solde insuffisant sur le +237670000000')",
                tenantId, schema, userId, discountId, "Remise de Jean, " + schema, "KVT-" + UUID.randomUUID());
        jdbc.update("INSERT INTO public.expo_push_tickets (ticket_id, token, tenant_schema) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(), "ExponentPushToken[" + digits(12) + "]", schema);
        jdbc.update("INSERT INTO public.web_push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent) "
                + "VALUES (gen_random_uuid(), ?, ?, 'p256dh', 'auth', 'Mozilla/5.0 (Jean)')",
                userId, "https://push.example.test/" + UUID.randomUUID());
    }

    /** 16.6 reprise — every later-table row of the deleted tenant gone; the payment request kept, redacted. */
    private void assertLaterPublicRowsGone(Seed seed) {
        assertThat(count("SELECT count(*) FROM public.whatsapp_accounts WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.mcp_access_tokens WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.ai_tool_calls WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.tenant_module_activation WHERE tenant_id = ?", seed.tenantId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM public.discounts WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.expo_push_tickets WHERE tenant_schema = ?", seed.schema())).isZero();
        assertThat(count("SELECT count(*) FROM public.offer_change_log WHERE summary LIKE ?", "%" + seed.schema() + "%"))
                .isZero();
        Map<String, Object> request = jdbc.queryForMap("SELECT phone, failure_reason, discount_id, discount_label, amount_due_xaf "
                + "FROM public.payment_requests WHERE tenant_id = ?", seed.tenantId());
        assertThat(request.get("phone")).isNull();
        assertThat(request.get("failure_reason")).isNull();
        assertThat(request.get("discount_id")).isNull();
        assertThat(request.get("discount_label")).isEqualTo("Remise");
        assertThat(((Number) request.get("amount_due_xaf")).longValue()).isEqualTo(4500L);
    }

    /**
     * A tenant with an OWNER and an EMPLOYEE (reset token created 1 h ago), all its public rows, a
     * company designation, billing rows, an export directory, and an owner deletion request whose
     * grace period is over.
     */
    private Seed seedRequestedAndExpired() throws Exception {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        UUID ownerId = insertUser(randomPhone(), "OWNER");
        insertMembership(tenant.getId(), ownerId, "OWNER");
        String employeePhone = randomPhone();
        UUID employeeId = insertUser(employeePhone, "EMPLOYEE");
        insertMembership(tenant.getId(), employeeId, "EMPLOYEE");
        insertResetToken(employeeId, employeePhone);
        jdbc.update("INSERT INTO public.refresh_tokens (id, created_at, updated_at, expires_at, revoked, tenant_id, "
                + "token_hash, user_id) VALUES (?, now(), now(), now() + interval '30 days', false, ?, ?, ?)",
                UUID.randomUUID(), tenant.getSchemaName(), "hash-" + UUID.randomUUID(), employeeId);
        insertTenantPublicRows(tenant.getId(), tenant.getSchemaName(), employeeId);
        jdbc.update("UPDATE public.tenants SET designation = 'Ets Jean Dupont', suspended_reason = 'Note admin' "
                + "WHERE id = ?", tenant.getId());
        jdbc.update("INSERT INTO public.tenant_payments (tenant_id, amount_xaf, method, paid_at, period_start, "
                + "period_end, recorded_by, note) VALUES (?, 5000, 'CASH', now(), ?, ?, ?, 'Payé par Jean Dupont')",
                tenant.getId(), LocalDate.now(), LocalDate.now().plusMonths(1), UUID.randomUUID());
        jdbc.update("INSERT INTO public.tenant_payments (tenant_id, amount_xaf, method, paid_at, period_start, "
                + "period_end, recorded_by, voided_at, voided_by, void_reason) VALUES (?, 3000, 'MOBILE_MONEY', now(), "
                + "?, ?, ?, now(), ?, 'Doublon, Jean a payé deux fois')",
                tenant.getId(), LocalDate.now(), LocalDate.now().plusMonths(1), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, actor_id, reason) "
                + "VALUES (?, 'FREE', 'PREMIUM', 'ADMIN_ACTIVATION', ?, 'Geste commercial pour Jean')",
                tenant.getId(), UUID.randomUUID());
        jdbc.update("INSERT INTO public.global_notifications (id, title, body, target, target_tenant_id, "
                + "scheduled_at, status, created_by) VALUES (?, 'Bonjour Jean', 'Votre abonnement', 'TENANT', ?, "
                + "now() + interval '1 day', 'SCHEDULED', ?)", UUID.randomUUID(), tenant.getId(), UUID.randomUUID());
        Path exportDir = Files.createDirectories(Path.of(exportStorageDir).resolve(tenant.getSchemaName()));
        Files.writeString(exportDir.resolve(UUID.randomUUID() + ".csv"), "Nom;Téléphone\nJean;699000000\n");

        accountDeletionService.requestDeletion(ownerId, tenant.getSchemaName());
        jdbc.update("UPDATE public.tenants SET deletion_scheduled_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), tenant.getId());
        return new Seed(tenant.getId(), tenant.getSchemaName(), tenant.getCode(), ownerId, employeeId,
                employeePhone, exportDir);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String status(UUID tenantId) {
        return jdbc.queryForObject("SELECT status FROM public.tenants WHERE id = ?", String.class, tenantId);
    }

    private boolean schemaExists(String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)", Boolean.class, schema));
    }

    /** {@code event/source} of the tenant's trace, oldest first. */
    private List<String> trace(UUID tenantId) {
        return jdbc.queryForList("SELECT event || '/' || source FROM public.account_deletion_log "
                + "WHERE tenant_id = ? ORDER BY occurred_at", String.class, tenantId);
    }

    private void assertTenantPublicRowsGone(Seed seed) {
        assertThat(count("SELECT count(*) FROM public.feedback WHERE tenant_id = ?", seed.schema())).isZero();
        assertThat(count("SELECT count(*) FROM public.user_sync_state WHERE tenant_id = ?", seed.schema())).isZero();
        assertThat(count("SELECT count(*) FROM public.invoice_legacy_links WHERE tenant_schema = ?", seed.schema()))
                .isZero(); // Story 20.7
        assertThat(count("SELECT count(*) FROM public.user_daily_activity WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.tenant_modules WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.plan_expiry_reminders WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.sync_silence_notices WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.usage_counters WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.usage_credits WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.tenant_credits WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.tenant_credit_movements WHERE tenant_id = ?", seed.tenantId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM public.usage_credit_movements WHERE tenant_id = ?", seed.tenantId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.refresh_tokens WHERE tenant_id = ?", seed.schema())).isZero();
        assertThat(count("SELECT count(*) FROM public.global_notifications WHERE target_tenant_id = ?", seed.tenantId())).isZero();
        // Billing rows kept (accounting trace) without their admin free text
        assertThat(count("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ?", seed.tenantId())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ? AND note IS NOT NULL",
                seed.tenantId())).isZero();
        assertThat(jdbc.queryForList("SELECT void_reason FROM public.tenant_payments WHERE tenant_id = ? "
                + "AND voided_at IS NOT NULL", String.class, seed.tenantId())).containsExactly("Motif supprimé (RGPD)");
        assertThat(count("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ? AND voided_at IS NULL "
                + "AND void_reason IS NOT NULL", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ? AND reason IS NOT NULL",
                seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ? AND cause = 'ADMIN_ACTIVATION'",
                seed.tenantId())).isEqualTo(1);
        assertLaterPublicRowsGone(seed);
    }

    // ── Matrix ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("daily run after the grace: schema, public rows, users, tokens and export files gone; "
            + "tenants row anonymised; REQUESTED/OWNER then EXECUTED/SYSTEM; second run adds nothing")
    void dailyRunPurgesEverythingAndTracesIt() throws Exception {
        Seed seed = seedRequestedAndExpired();
        assertThat(trace(seed.tenantId())).containsExactly("REQUESTED/OWNER");
        assertThat(jdbc.queryForObject("SELECT scheduled_for FROM public.account_deletion_log WHERE tenant_id = ?",
                Timestamp.class, seed.tenantId())).isNotNull();
        TenantDeletionService scheduler = scheduler(jdbc);

        assertThat(scheduler.runTenantDeletion(seed.tenantId(), true)).isTrue();

        assertThat(schemaExists(seed.schema())).isFalse();
        assertTenantPublicRowsGone(seed);
        // Orphaned users and their reset token (recent, no cascade) are gone — no postponement
        assertThat(count("SELECT count(*) FROM public.users WHERE id IN (?, ?)", seed.ownerId(), seed.employeeId())).isZero();
        assertThat(count("SELECT count(*) FROM public.password_reset_tokens WHERE user_id = ?", seed.employeeId())).isZero();
        assertThat(count("SELECT count(*) FROM public.password_reset_tokens WHERE phone_number = ?", seed.employeePhone())).isZero();
        // 16.6 reprise — the orphan's browser push subscription (no FK to users) is gone too
        assertThat(count("SELECT count(*) FROM public.web_push_subscriptions WHERE user_id = ?", seed.employeeId())).isZero();
        // Anonymised kept row: code, schema, DELETED, billing kept; no name / designation / free text
        Map<String, Object> row = jdbc.queryForMap("SELECT code, schema_name, status, name, designation, "
                + "suspended_reason, deletion_scheduled_at FROM public.tenants WHERE id = ?", seed.tenantId());
        assertThat(row.get("code")).isEqualTo(seed.code());
        assertThat(row.get("schema_name")).isEqualTo(seed.schema());
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("name")).isEqualTo("Commerce supprimé");
        assertThat(row.get("designation")).isNull();
        assertThat(row.get("suspended_reason")).isNull();
        assertThat(row.get("deletion_scheduled_at")).isNull();
        // Export files purged after commit
        assertThat(seed.exportDir()).doesNotExist();
        // Durable trace
        assertThat(trace(seed.tenantId())).containsExactly("REQUESTED/OWNER", "EXECUTED/SYSTEM");
        assertThat(jdbc.queryForList("SELECT DISTINCT tenant_code FROM public.account_deletion_log WHERE tenant_id = ?",
                String.class, seed.tenantId())).containsExactly(seed.code());

        // Second run: already DELETED → nothing, no second EXECUTED
        assertThat(scheduler.runTenantDeletion(seed.tenantId(), true)).isFalse();
        scheduler.executeDeletions();
        assertThat(trace(seed.tenantId())).containsExactly("REQUESTED/OWNER", "EXECUTED/SYSTEM");
    }

    @Test
    @DisplayName("the trace table holds no personal data: ids, account code, event, source and dates only")
    void traceTableHasNoPersonalColumns() {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'account_deletion_log'", String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "tenant_id", "tenant_code", "event", "source", "scheduled_for", "occurred_at");
    }

    @Test
    @DisplayName("a user also member of tenant B keeps the account, the B membership, B's data and the reset token")
    void sharedUserKeepsEverythingOfTheOtherTenant() throws Exception {
        Seed seed = seedRequestedAndExpired();
        Tenant other = tx.execute(s -> tenantFactory.create());
        insertMembership(other.getId(), seed.employeeId(), "EMPLOYEE");
        insertTenantPublicRows(other.getId(), other.getSchemaName(), seed.employeeId());

        assertThat(scheduler(jdbc).runTenantDeletion(seed.tenantId(), true)).isTrue();

        assertTenantPublicRowsGone(seed);
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", seed.employeeId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", seed.ownerId())).isZero();
        assertThat(count("SELECT count(*) FROM public.password_reset_tokens WHERE user_id = ?", seed.employeeId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.feedback WHERE tenant_id = ?", other.getSchemaName())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.user_sync_state WHERE tenant_id = ?", other.getSchemaName())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.invoice_legacy_links WHERE tenant_schema = ?",
                other.getSchemaName())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.user_daily_activity WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.usage_counters WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.usage_credits WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.tenant_credits WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.tenant_credit_movements WHERE tenant_id = ?", other.getId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.usage_credit_movements WHERE tenant_id = ?", other.getId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.tenant_modules WHERE tenant_id = ? AND module_id = 'rgpd-test'",
                other.getId())).isEqualTo(1);
        // 16.6 reprise — tenant B's later-table rows and the shared user's push subscriptions are untouched
        assertThat(count("SELECT count(*) FROM public.whatsapp_accounts WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.mcp_access_tokens WHERE tenant_id = ? AND user_id = ?",
                other.getId(), seed.employeeId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.ai_tool_calls WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.tenant_module_activation WHERE tenant_id = ? "
                + "AND module_id = 'rgpd-test'", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.discounts WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.offer_change_log WHERE summary LIKE ?",
                "%" + other.getSchemaName() + "%")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.payment_requests WHERE tenant_id = ? AND phone IS NOT NULL",
                other.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.expo_push_tickets WHERE tenant_schema = ?", other.getSchemaName()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.web_push_subscriptions WHERE user_id = ?", seed.employeeId()))
                .isEqualTo(2);
        assertThat(status(other.getId())).isEqualTo("ACTIVE");
        assertThat(schemaExists(other.getSchemaName())).isTrue();
        // test row only: never leave an unknown module id in the shared database
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ? AND module_id = 'rgpd-test'", other.getId());
        jdbc.update("DELETE FROM public.tenant_module_activation WHERE tenant_id = ? AND module_id = 'rgpd-test'",
                other.getId());
    }

    @Test
    @DisplayName("failure after the DROP: public changes and EXECUTED rolled back, still pending; the retry completes with one EXECUTED")
    void failureAfterDropRollsBackTheTraceToo() throws Exception {
        Seed seed = seedRequestedAndExpired();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        JdbcTemplate failingOnce = new JdbcTemplate(dataSource) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM public.sync_silence_notices") && args.length == 1
                        && seed.tenantId().equals(args[0]) && failOnce.getAndSet(false)) {
                    throw new IllegalStateException("simulated failure after the DROP");
                }
                return super.update(sql, args);
            }
        };
        TenantDeletionService scheduler = scheduler(failingOnce);

        try {
            scheduler.runTenantDeletion(seed.tenantId(), true);
        } catch (IllegalStateException expected) {
            // the daily loop logs it; here we call the routine directly
        }

        assertThat(schemaExists(seed.schema())).isFalse();
        assertThat(status(seed.tenantId())).isEqualTo("DELETION_PENDING");
        assertThat(count("SELECT count(*) FROM public.feedback WHERE tenant_id = ?", seed.schema())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.password_reset_tokens WHERE user_id = ?", seed.employeeId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM public.tenants WHERE id = ?", String.class, seed.tenantId()))
                .isNotEqualTo("Commerce supprimé");
        assertThat(trace(seed.tenantId())).containsExactly("REQUESTED/OWNER");
        assertThat(count("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ? AND note IS NOT NULL",
                seed.tenantId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.global_notifications WHERE target_tenant_id = ?", seed.tenantId()))
                .isEqualTo(1);

        assertThat(scheduler.runTenantDeletion(seed.tenantId(), true)).isTrue();

        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertTenantPublicRowsGone(seed);
        assertThat(trace(seed.tenantId())).containsExactly("REQUESTED/OWNER", "EXECUTED/SYSTEM");
    }

    @Test
    @DisplayName("owner cancel: ACTIVE + CANCELLED/OWNER")
    void ownerCancelIsTraced() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        UUID ownerId = insertUser(randomPhone(), "OWNER");
        insertMembership(tenant.getId(), ownerId, "OWNER");

        accountDeletionService.requestDeletion(ownerId, tenant.getSchemaName());
        accountDeletionService.cancelDeletion(ownerId, tenant.getSchemaName());

        assertThat(status(tenant.getId())).isEqualTo("ACTIVE");
        assertThat(trace(tenant.getId())).containsExactly("REQUESTED/OWNER", "CANCELLED/OWNER");
    }

    @Test
    @DisplayName("admin force-delete: REQUESTED/ADMIN then EXECUTED/ADMIN, same purge")
    void adminForceDeleteIsTraced() throws Exception {
        Seed seed = seedRequestedAndExpired();
        jdbc.update("UPDATE public.tenants SET status = 'ACTIVE', deletion_scheduled_at = NULL WHERE id = ?",
                seed.tenantId());
        jdbc.update("DELETE FROM public.account_deletion_log WHERE tenant_id = ?", seed.tenantId());

        adminTenantService.forceDelete(seed.tenantId());

        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertThat(schemaExists(seed.schema())).isFalse();
        assertTenantPublicRowsGone(seed);
        assertThat(count("SELECT count(*) FROM public.users WHERE id IN (?, ?)", seed.ownerId(), seed.employeeId())).isZero();
        assertThat(seed.exportDir()).doesNotExist();
        assertThat(trace(seed.tenantId())).containsExactly("REQUESTED/ADMIN", "EXECUTED/ADMIN");
        assertThat(jdbc.queryForObject("SELECT scheduled_for FROM public.account_deletion_log WHERE tenant_id = ? "
                + "AND event = 'REQUESTED'", Timestamp.class, seed.tenantId())).isNotNull();
    }

    @Test
    @DisplayName("admin force-delete: the REQUESTED date is exactly the marker written on the tenant row")
    void adminForceDeleteTraceEqualsTheMarker() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        TenantSchemaProvisioner failingDrop = mock(TenantSchemaProvisioner.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated DROP failure"))
                .when(failingDrop).dropSchemaForDeletion(org.mockito.ArgumentMatchers.anyString());
        TenantDeletionService failing = new TenantDeletionService(tenantRepository, userRepository, whatsAppSender,
                failingDrop, jdbc, transactionManager, Clock.systemUTC(), filePurgers, deletionLog);
        AdminTenantService service = new AdminTenantService(jdbc,
                com.keevo.identity.auth.adapter.in.api.IdentityAuthApiTestSupport.tenantDeletionPort(failing, deletionLog));

        try {
            service.forceDelete(tenant.getId());
        } catch (IllegalStateException expected) {
            // the routine failed after the marker committed: the marker is still on the row
        }

        Timestamp marker = jdbc.queryForObject("SELECT deletion_scheduled_at FROM public.tenants WHERE id = ?",
                Timestamp.class, tenant.getId());
        assertThat(marker).isNotNull();
        assertThat(jdbc.queryForObject("SELECT scheduled_for FROM public.account_deletion_log WHERE tenant_id = ? "
                + "AND event = 'REQUESTED'", Timestamp.class, tenant.getId())).isEqualTo(marker);
        assertThat(trace(tenant.getId())).containsExactly("REQUESTED/ADMIN");
        // leave no expired DELETION_PENDING tenant behind for the other tests' daily runs
        assertThat(scheduler(jdbc).runTenantDeletion(tenant.getId(), true)).isTrue();
    }

    @Test
    @DisplayName("unknown tenant id: no exception, no row (warning logged)")
    void unknownTenantWritesNothing() {
        UUID unknown = UUID.randomUUID();

        deletionLog.record(unknown, AccountDeletionLogRepository.Event.CANCELLED,
                AccountDeletionLogRepository.Source.ADMIN, null);
        deletionLog.record(unknown, AccountDeletionLogRepository.Event.REQUESTED,
                AccountDeletionLogRepository.Source.ADMIN, null);

        assertThat(trace(unknown)).isEmpty();
    }

    @Test
    @DisplayName("admin cancel: ACTIVE + CANCELLED/ADMIN")
    void adminCancelIsTraced() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        UUID ownerId = insertUser(randomPhone(), "OWNER");
        insertMembership(tenant.getId(), ownerId, "OWNER");
        accountDeletionService.requestDeletion(ownerId, tenant.getSchemaName());

        adminTenantService.cancelDeletion(tenant.getId());

        assertThat(status(tenant.getId())).isEqualTo("ACTIVE");
        assertThat(trace(tenant.getId())).containsExactly("REQUESTED/OWNER", "CANCELLED/ADMIN");
    }
}
