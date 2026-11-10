package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.model.*;
import com.keevo.identity.auth.domain.port.out.AccountDeletionLogRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.capability.notification.PlatformMessageKind;
import com.keevo.shared.capability.notification.PlatformWhatsAppMessage;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.port.out.TenantFilesPurger;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TenantDeletionService — executes the definitive deletion of tenants whose 30-day grace period
 * has expired (Story 14.5, FR91), and the immediate deletion of the SUPER_ADMIN force-delete.
 *
 * <p>The daily run is triggered at 03:00 by {@code identity.auth.adapter.in.scheduling.AccountDeletionScheduler}
 * (ShedLock there); the immediate deletion is published as {@code identity.api.TenantDeletionPort}
 * (Story 12.2f split the routine from its trigger). Uses double constructor injection (real
 * {@link Clock} + test {@link Clock}).
 *
 * <p><b>Transactions (v1s-15-11):</b> there is no method-level {@code @Transactional}. Each
 * expired tenant is deleted in its own {@link TransactionTemplate} transaction, so one tenant's
 * SQL failure rolls back only that tenant and never aborts the run for the others. The final
 * WhatsApp is sent only after that transaction has committed, so a failed or retried run never
 * sends it twice.
 */
@Component
public class TenantDeletionService {

    private static final Logger log = LoggerFactory.getLogger(TenantDeletionService.class);

    /**
     * Row lock + re-check. A cancel whose UPDATE is already in flight (uncommitted) when the
     * scheduler locks makes the scheduler wait, then see the cancelled row and skip it. Conversely,
     * since v1s-15-9 every cancel is a single conditional UPDATE ({@code WHERE status =
     * 'DELETION_PENDING' AND to_regnamespace(schema_name) IS NOT NULL}, plus a grace-period guard
     * for the owner): a cancel issued while we hold this lock waits, then re-evaluates against the
     * committed DELETED row and matches nothing. The former residual race (blind ACTIVE write over
     * DELETED) is closed — proven by {@code AccountDeletionConcurrencyPostgresIntegrationTest}.
     */
    static final String LOCK_TENANT_SQL =
            "SELECT status, deletion_scheduled_at FROM public.tenants WHERE id = ? FOR UPDATE";

    /**
     * Story 19.3b — copies the stored Apple tokens of the users about to be deleted (no membership left) to the
     * revocation outbox. Same orphan predicate as the users DELETE; set-based on the {@code user_id} index.
     */
    static final String QUEUE_APPLE_REVOCATIONS_SQL =
            "INSERT INTO public.apple_token_revocations (authenticator_id, client_id, token) " +
            "SELECT a.id, a.revocation_client_id, a.revocation_token FROM public.user_authenticators a " +
            "WHERE a.user_id = ANY(?::uuid[]) " +
            "AND a.revocation_token IS NOT NULL AND a.revocation_client_id IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.user_id = a.user_id)";

    /** Story 16.6 (RGPD) — name kept on the anonymised {@code public.tenants} row. */
    static final String DELETED_TENANT_NAME = "Commerce supprimé";

    /** Story 16.6 (RGPD) — replaces the free-text reason of a voided payment of a deleted tenant. */
    static final String VOID_REASON_REDACTED = "Motif supprimé (RGPD)";

    /** Story 16.6 reprise — replaces a company-specific discount label copied on a kept payment request. */
    static final String DISCOUNT_LABEL_REDACTED = "Remise";

    /** Story 16.6 reprise — replaces the catalogue journal summary of a deleted company's discount. */
    static final String DISCOUNT_JOURNAL_REDACTED = "Remise réservée à une entreprise supprimée";

    static final String FINAL_WHATSAPP_MESSAGE =
            "Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo.";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final WhatsAppSender whatsAppSender;
    private final TenantSchemaProvisioner schemaProvisioner;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    /** Files stored outside the database (product photos…), deleted with the tenant (epic 8). */
    private final List<TenantFilesPurger> filePurgers;
    /** Story 16.6 — durable, non-personal trace of the execution (EXECUTED row). */
    private final AccountDeletionLogRepository deletionLog;

    /** Production constructor — system clock. */
    @org.springframework.beans.factory.annotation.Autowired
    public TenantDeletionService(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppSender whatsAppSender,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate,
                                     PlatformTransactionManager transactionManager,
                                     List<TenantFilesPurger> filePurgers,
                                     AccountDeletionLogRepository deletionLog) {
        this(tenantRepository, userRepository, whatsAppSender, schemaProvisioner,
             jdbcTemplate, transactionManager, Clock.systemUTC(), filePurgers, deletionLog);
    }

    /** Test constructor — injectable clock. */
    public TenantDeletionService(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppSender whatsAppSender,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this(tenantRepository, userRepository, whatsAppSender, schemaProvisioner,
             jdbcTemplate, transactionManager, clock, List.of());
    }

    /** Test constructor — injectable clock and file purgers, no deletion trace. */
    public TenantDeletionService(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppSender whatsAppSender,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock,
                                     List<TenantFilesPurger> filePurgers) {
        this(tenantRepository, userRepository, whatsAppSender, schemaProvisioner,
             jdbcTemplate, transactionManager, clock, filePurgers, (id, event, source, at) -> { });
    }

    /** Full constructor — injectable clock, file purgers and deletion trace. */
    public TenantDeletionService(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppSender whatsAppSender,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock,
                                     List<TenantFilesPurger> filePurgers,
                                     AccountDeletionLogRepository deletionLog) {
        this.deletionLog = java.util.Objects.requireNonNull(deletionLog, "deletionLog");
        this.filePurgers = filePurgers != null ? List.copyOf(filePurgers) : List.of();
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.whatsAppSender = whatsAppSender;
        this.schemaProvisioner = schemaProvisioner;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Daily run — processes all tenants whose grace period has expired.
     * Each tenant runs in its own transaction (see class javadoc).
     */
    public void executeDeletions() {
        log.info("[TenantDeletionService] Starting daily deletion check...");
        Instant now = clock.instant();

        // v1s-15-9: only the due DELETION_PENDING tenants, not every tenant
        List<Tenant> due = tenantRepository.findDeletionsDueBy(now);
        int processed = 0;

        for (Tenant tenant : due) {
            try {
                if (runTenantDeletion(tenant.getId(), true)) processed++;
            } catch (Exception e) {
                log.error("[TenantDeletionService] Failed to delete tenant {} ({}): {}",
                        tenant.getId(), tenant.getSchemaName(), e.getMessage(), e);
                // Per-tenant transaction rolled back: still DELETION_PENDING, retried on the next daily run
            }
        }

        log.info("[TenantDeletionService] Processed {} tenant deletion(s).", processed);
    }

    /**
     * v1s-15-9 — immediate deletion for the SUPER_ADMIN force-delete, through the exact routine
     * the daily run uses (row lock, DROP, purge, DELETED last, WhatsApp after commit). The caller
     * must first commit the tenant as DELETION_PENDING. Not {@code @SchedulerLock}ed: the row lock
     * already serialises it with a concurrent daily run. Exceptions propagate to the caller.
     *
     * @return {@code true} when the tenant was deleted, {@code false} when it was no longer
     *         DELETION_PENDING (e.g. cancelled or deleted concurrently)
     */
    public boolean deleteNow(UUID tenantId) {
        return runTenantDeletion(tenantId, false);
    }

    /**
     * One tenant, one transaction; the final WhatsApp is sent only after commit, so a rolled-back
     * or retried deletion never sends it twice.
     *
     * @param requireExpired {@code true} for the daily run (grace period must be over by the
     *                       scheduler clock); {@code false} for {@link #deleteNow}
     * @return {@code true} when deleted, {@code false} when skipped
     */
    boolean runTenantDeletion(UUID tenantId, boolean requireExpired) {
        String[] schemaName = new String[1];
        List<String> ownerPhones = transactionTemplate.execute(
                status -> executeDeletionForTenant(tenantId, requireExpired, schemaName));
        if (ownerPhones == null) return false; // skipped (cancelled / no longer pending)
        purgeFiles(tenantId, schemaName[0]);
        sendFinalWhatsApp(tenantId, ownerPhones);
        return true;
    }

    /**
     * Epic 8: « uploaded photos » go with the account. After commit (files are not
     * transactional): a rolled-back deletion keeps them. Best effort — a purger never throws.
     */
    private void purgeFiles(UUID tenantId, String schemaName) {
        if (schemaName == null) return;
        for (TenantFilesPurger purger : filePurgers) {
            try {
                purger.purgeTenant(schemaName);
            } catch (Exception e) {
                log.error("[TenantDeletionService] File purge failed for tenant {}: {}",
                        tenantId, e.getMessage(), e);
            }
        }
    }

    /**
     * Execute complete deletion for a single tenant, inside the caller's per-tenant transaction.
     *
     * <p><b>Order is critical</b> (v1s-15-11 supersedes the 14-5 DN1 "DELETED before DROP" order,
     * which left a DELETED tenant with a lingering schema that the loop never retried):
     * <ol>
     *   <li>Lock the tenant row ({@code FOR UPDATE}) and skip it unless still DELETION_PENDING (with an
     *       expired {@code deletion_scheduled_at} when {@code requireExpired}).</li>
     *   <li>Capture the OWNER phone numbers (memberships still exist).</li>
     *   <li>DROP schema CASCADE — own auto-commit connection, {@code IF EXISTS} so idempotent.
     *       If it throws, the transaction rolls back and nothing is persisted.</li>
     *   <li>Delete memberships, the password-reset tokens then the rows of orphaned users, refresh
     *       tokens, device tokens (orphans only).</li>
     *   <li>Story 16.6 (RGPD): delete the tenant's remaining {@code public} rows (feedback, daily
     *       activity, sync state, module licences, plan reminders, silence notices, tenant-targeted
     *       push announcements), clear the admin free text of the kept billing rows, write the
     *       EXECUTED trace, anonymise the kept tenants row.</li>
     *   <li>Mark tenant DELETED — last, so DELETED always means "schema gone".</li>
     * </ol>
     * A failure after a successful DROP rolls back the public deletes: the tenant stays
     * DELETION_PENDING and the next run completes it ({@code DROP IF EXISTS} is then a no-op).
     *
     * @return the OWNER phone numbers to notify after commit, or {@code null} when skipped
     */
    private List<String> executeDeletionForTenant(UUID tenantId, boolean requireExpired, String[] schemaOut) {
        // 1. Row lock + re-check of status (AND expiry for the daily run: detects a cancellation —
        //    or a cancel then a new request with a future date — since findDeletionsDueBy)
        List<Map<String, Object>> locked = jdbcTemplate.queryForList(LOCK_TENANT_SQL, tenantId);
        if (locked.isEmpty() || !isDeletable(locked.get(0), requireExpired, clock.instant())) {
            log.info("[TenantDeletionService] Tenant {} no longer expired DELETION_PENDING (cancelled?), skipping.",
                    tenantId);
            return null;
        }
        Tenant fresh = tenantRepository.findById(tenantId).orElse(null);
        if (fresh == null) return null;
        String schemaName = fresh.getSchemaName();
        schemaOut[0] = schemaName;

        // 2. OWNER phones, captured before memberships/users are deleted
        List<String> ownerPhones = findOwnerPhones(tenantId);

        // 3. DROP schema CASCADE (propagates on failure → rollback, tenant stays DELETION_PENDING)
        schemaProvisioner.dropSchemaForDeletion(schemaName);

        // 4. Capture affected user IDs BEFORE any deletion
        List<UUID> affectedUserIds = jdbcTemplate.queryForList(
                "SELECT user_id FROM public.user_tenant_memberships WHERE tenant_id = ?",
                UUID.class, tenantId);

        // 5. Delete memberships for this tenant
        jdbcTemplate.update("DELETE FROM public.user_tenant_memberships WHERE tenant_id = ?",
                (Object) tenantId);

        // 6. Delete ONLY orphaned users (not multi-tenant users)
        List<UUID> orphanedUserIds = new ArrayList<>();
        if (!affectedUserIds.isEmpty()) {
            // 16.6: password_reset_tokens references users without cascade — a recent token made the
            // user DELETE fail and postponed the whole deletion. Delete the orphans' tokens first (a
            // user still member of another tenant keeps its own tokens).
            jdbcTemplate.update(
                    "DELETE FROM public.password_reset_tokens p " +
                    "WHERE p.user_id = ANY(?::uuid[]) " +
                    "AND NOT EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.user_id = p.user_id)",
                    (Object) affectedUserIds.toArray(new UUID[0]));
            // Story 19.3b (App Store) — the orphans' Sign in with Apple tokens go to the revocation outbox in this
            // transaction, before their authenticators cascade away with them (drained by AppleTokenRevocationJob).
            jdbcTemplate.update(QUEUE_APPLE_REVOCATIONS_SQL, (Object) affectedUserIds.toArray(new UUID[0]));
            List<UUID> deletedIds = jdbcTemplate.queryForList(
                    "DELETE FROM public.users u " +
                    "WHERE u.id = ANY(?::uuid[]) " +
                    "AND NOT EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.user_id = u.id) " +
                    "RETURNING u.id",
                    // (Object) cast: a bare UUID[] would be spread as varargs (one bind per id) and
                    // PostgreSQL rejects "uuid::uuid[]" — found by the v1s-15-11 real-DB test
                    UUID.class, (Object) affectedUserIds.toArray(new UUID[0]));
            orphanedUserIds.addAll(deletedIds);
        }

        // 7. Delete refresh tokens for this tenant
        jdbcTemplate.update("DELETE FROM public.refresh_tokens WHERE tenant_id = ?",
                (Object) schemaName);

        // 8. Delete device tokens for orphaned users only
        if (!orphanedUserIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM public.device_tokens WHERE user_id = ANY(?::uuid[])",
                    (Object) orphanedUserIds.toArray(new UUID[0]));
            // 16.6 reprise — browser push subscriptions (endpoint, user agent; no FK to users, story 19.9b)
            jdbcTemplate.update("DELETE FROM public.web_push_subscriptions WHERE user_id = ANY(?::uuid[])",
                    (Object) orphanedUserIds.toArray(new UUID[0]));
        }

        // 9. Story 16.6 (RGPD): the tenant's other public rows. Tenant columns differ per table:
        //    schema name (feedback, user_sync_state) or tenant uuid (the others).
        jdbcTemplate.update("DELETE FROM public.feedback WHERE tenant_id = ?", (Object) schemaName);
        jdbcTemplate.update("DELETE FROM public.user_sync_state WHERE tenant_id = ?", (Object) schemaName);
        // Story 20.7 — the old Bref links of the tenant's imported invoices stop resolving
        jdbcTemplate.update("DELETE FROM public.invoice_legacy_links WHERE tenant_schema = ?", (Object) schemaName);
        jdbcTemplate.update("DELETE FROM public.user_daily_activity WHERE tenant_id = ?", (Object) tenantId);
        jdbcTemplate.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", (Object) tenantId);
        jdbcTemplate.update("DELETE FROM public.plan_expiry_reminders WHERE tenant_id = ?", (Object) tenantId);
        jdbcTemplate.update("DELETE FROM public.sync_silence_notices WHERE tenant_id = ?", (Object) tenantId);
        // Story 19.8 — usage counters and packs (ON DELETE CASCADE too, but the tenant row is kept)
        jdbcTemplate.update("DELETE FROM public.usage_counters WHERE tenant_id = ?", (Object) tenantId);
        jdbcTemplate.update("DELETE FROM public.usage_credits WHERE tenant_id = ?", (Object) tenantId);
        // Story 21.3 — pack journal (actors), account credit and its journal (support reasons): the cash trace stays
        // in tenant_payments / payment_requests
        jdbcTemplate.update("DELETE FROM public.usage_credit_movements WHERE tenant_id = ?", (Object) tenantId);
        jdbcTemplate.update("DELETE FROM public.tenant_credit_movements WHERE tenant_id = ?", (Object) tenantId);
        jdbcTemplate.update("DELETE FROM public.tenant_credits WHERE tenant_id = ?", (Object) tenantId);
        // Free text not mapped by the JPA entity (company designation, admin suspension reason)
        jdbcTemplate.update("UPDATE public.tenants SET designation = NULL, suspended_reason = NULL WHERE id = ?",
                (Object) tenantId);
        // Billing rows are the accounting trace (amounts, dates, ids kept) but their admin-written free
        // text may name the owner: notes and reasons cleared, a void keeps a neutral reason.
        jdbcTemplate.update("UPDATE public.tenant_payments SET note = NULL, "
                + "void_reason = CASE WHEN void_reason IS NULL THEN NULL ELSE ? END WHERE tenant_id = ?",
                VOID_REASON_REDACTED, tenantId);
        jdbcTemplate.update("UPDATE public.tenant_plan_events SET reason = NULL WHERE tenant_id = ?",
                (Object) tenantId);
        // Push announcements written for this tenant only (title/body); no table references them
        jdbcTemplate.update("DELETE FROM public.global_notifications WHERE target_tenant_id = ?",
                (Object) tenantId);
        purgeLaterPublicRows(tenantId, schemaName);

        // 10. Durable, non-personal trace — same transaction: rolled back with a failed purge
        deletionLog.record(tenantId, AccountDeletionLogRepository.Event.EXECUTED,
                requireExpired ? AccountDeletionLogRepository.Source.SYSTEM
                               : AccountDeletionLogRepository.Source.ADMIN, null);

        // 11. Mark tenant DELETED (name anonymised) — only once the schema is gone
        tenantRepository.save(fresh.anonymisedAsDeleted(DELETED_TENANT_NAME));

        log.info("[TenantDeletionService] Tenant {} ({}) fully deleted: {} users orphaned, schema dropped.",
                tenantId, schemaName, orphanedUserIds.size());
        return ownerPhones;
    }

    /**
     * Story 16.6 reprise (2026-11-10) — the tenant's rows in the public tables added after V40 (stories 19.9b–21.10).
     * They are declared {@code ON DELETE CASCADE} on {@code tenants}, but the deletion keeps the (anonymised) tenant
     * row, so nothing cascades. One set-based statement per table, FK-safe order: the company-specific discount labels
     * are redacted where they were copied (payment requests, catalogue journal — whose summary also quotes the company
     * name) BEFORE the discounts go. Payment requests stay (payment trace) without the payer's phone.
     */
    private void purgeLaterPublicRows(UUID tenantId, String schemaName) {
        jdbcTemplate.update("UPDATE public.payment_requests p SET discount_label = ? FROM public.discounts d "
                + "WHERE d.id = p.discount_id AND d.tenant_id = ? AND p.tenant_id = ?",
                DISCOUNT_LABEL_REDACTED, tenantId, tenantId);
        jdbcTemplate.update("UPDATE public.offer_change_log SET summary = ? WHERE entity = 'DISCOUNT' "
                + "AND entity_key IN (SELECT id::text FROM public.discounts WHERE tenant_id = ?)",
                DISCOUNT_JOURNAL_REDACTED, tenantId);
        jdbcTemplate.update("DELETE FROM public.discounts WHERE tenant_id = ?", (Object) tenantId);
        // payer phone and the provider's free-text failure message (may echo the number) cleared; amounts, refs kept
        jdbcTemplate.update("UPDATE public.payment_requests SET phone = NULL, failure_reason = NULL WHERE tenant_id = ? "
                + "AND (phone IS NOT NULL OR failure_reason IS NOT NULL)", (Object) tenantId);
        // 21.5 — encrypted WhatsApp Business token, WABA and number ids (the inbox went with the schema)
        jdbcTemplate.update("DELETE FROM public.whatsapp_accounts WHERE tenant_id = ?", (Object) tenantId);
        // 21.6 — campaign work queue rows; the owner's contact data frozen in platform campaign recipients (counts kept)
        jdbcTemplate.update("DELETE FROM public.whatsapp_campaign_jobs WHERE tenant_id = ?", (Object) tenantId);
        jdbcTemplate.update("UPDATE public.platform_campaign_recipients SET address = NULL, first_name = NULL, "
                + "last_name = NULL, company_name = NULL WHERE tenant_id = ?", (Object) tenantId);
        // 21.9 — MCP personal access tokens (a multi-tenant owner keeps the user row, so no user cascade)
        jdbcTemplate.update("DELETE FROM public.mcp_access_tokens WHERE tenant_id = ?", (Object) tenantId);
        // 21.8 — tool-call journal (actor ids)
        jdbcTemplate.update("DELETE FROM public.ai_tool_calls WHERE tenant_id = ?", (Object) tenantId);
        // 19.13 — owner module activations (actor id)
        jdbcTemplate.update("DELETE FROM public.tenant_module_activation WHERE tenant_id = ?", (Object) tenantId);
        // 19.9b — Expo tickets (device push tokens) of the dropped schema
        jdbcTemplate.update("DELETE FROM public.expo_push_tickets WHERE tenant_schema = ?", (Object) schemaName);
    }

    private static boolean isDeletable(Map<String, Object> row, boolean requireExpired, Instant now) {
        if (!TenantStatus.DELETION_PENDING.name().equals(row.get("status"))) return false;
        if (!requireExpired) return true;
        Instant scheduledAt = toInstant(row.get("deletion_scheduled_at"));
        return scheduledAt != null && !scheduledAt.isAfter(now);
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        throw new IllegalStateException("Unexpected deletion_scheduled_at type: " + value.getClass());
    }

    private List<String> findOwnerPhones(UUID tenantId) {
        List<UUID> ownerUserIds = jdbcTemplate.queryForList(
                "SELECT user_id FROM public.user_tenant_memberships WHERE tenant_id = ? AND role = 'OWNER'",
                UUID.class, tenantId);
        List<String> phones = new ArrayList<>();
        for (UUID ownerId : ownerUserIds) {
            userRepository.findById(ownerId).map(User::getPhoneNumber).ifPresent(phones::add);
        }
        return phones;
    }

    private void sendFinalWhatsApp(UUID tenantId, List<String> ownerPhones) {
        for (String phone : ownerPhones) {
            try {
                whatsAppSender.sendNotification(phone,
                        PlatformWhatsAppMessage.fixed(PlatformMessageKind.ACCOUNT_DELETED, FINAL_WHATSAPP_MESSAGE));
            } catch (Exception e) {
                // Non-blocking — the deletion is already committed
                log.error("[TenantDeletionService] Failed to send final WhatsApp for tenant {}: {}",
                        tenantId, e.getMessage(), e);
            }
        }
    }
}
