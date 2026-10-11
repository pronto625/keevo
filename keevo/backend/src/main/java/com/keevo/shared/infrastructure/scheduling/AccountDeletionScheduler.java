package com.keevo.shared.infrastructure.scheduling;

import com.keevo.identity.auth.domain.model.*;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
 * AccountDeletionScheduler — daily job that executes the deletion of tenants
 * whose 30-day grace period has expired (Story 14.5, FR91).
 *
 * <p>Runs daily at 03:00 server time (offset from SubscriptionExpiryScheduler at 02:00).
 * Uses double constructor injection (real {@link Clock} + test {@link Clock}).
 * Mirror of {@link SubscriptionExpiryScheduler}.
 *
 * <p><b>Transactions (v1s-15-11):</b> there is no method-level {@code @Transactional}. Each
 * expired tenant is deleted in its own {@link TransactionTemplate} transaction, so one tenant's
 * SQL failure rolls back only that tenant and never aborts the run for the others. The final
 * WhatsApp is sent only after that transaction has committed, so a failed or retried run never
 * sends it twice.
 */
@Component
public class AccountDeletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionScheduler.class);

    /**
     * Row lock + re-check. Guarantee: a cancel whose UPDATE is already in flight (uncommitted) when
     * the scheduler locks makes the scheduler wait, then see the cancelled row and skip it. Not
     * guaranteed: {@code cancelDeletion} reads unlocked and then blind-writes ACTIVE, so a cancel
     * that read DELETION_PENDING before our commit can still overwrite DELETED afterwards — known
     * residual, routed to v1s-15-9 (conditional UPDATE {@code WHERE status = 'DELETION_PENDING'}).
     */
    static final String LOCK_TENANT_SQL =
            "SELECT status, deletion_scheduled_at FROM public.tenants WHERE id = ? FOR UPDATE";

    static final String FINAL_WHATSAPP_MESSAGE =
            "Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo.";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final WhatsAppPort whatsAppPort;
    private final TenantSchemaProvisioner schemaProvisioner;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** Production constructor — system clock. */
    @org.springframework.beans.factory.annotation.Autowired
    public AccountDeletionScheduler(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppPort whatsAppPort,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate,
                                     PlatformTransactionManager transactionManager) {
        this(tenantRepository, userRepository, whatsAppPort, schemaProvisioner,
             jdbcTemplate, transactionManager, Clock.systemUTC());
    }

    /** Test constructor — injectable clock. */
    public AccountDeletionScheduler(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppPort whatsAppPort,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.whatsAppPort = whatsAppPort;
        this.schemaProvisioner = schemaProvisioner;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Runs daily at 03:00 — processes all tenants whose grace period has expired.
     * Each tenant runs in its own transaction (see class javadoc).
     */
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "accountDeletionScheduler.executeDeletions", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void executeDeletions() {
        log.info("[AccountDeletionScheduler] Starting daily deletion check...");
        Instant now = clock.instant();

        List<Tenant> allTenants = tenantRepository.findAll();
        int processed = 0;

        for (Tenant tenant : allTenants) {
            if (tenant.getStatus() != TenantStatus.DELETION_PENDING) continue;

            Instant deletionScheduledAt = tenant.getDeletionScheduledAt();
            if (deletionScheduledAt == null || deletionScheduledAt.isAfter(now)) continue;

            List<String> ownerPhones;
            try {
                ownerPhones = transactionTemplate.execute(status -> executeDeletionForTenant(tenant.getId()));
            } catch (Exception e) {
                log.error("[AccountDeletionScheduler] Failed to delete tenant {} ({}): {}",
                        tenant.getId(), tenant.getSchemaName(), e.getMessage(), e);
                // Per-tenant transaction rolled back: still DELETION_PENDING, retried on the next daily run
                continue;
            }
            if (ownerPhones == null) continue; // skipped (cancelled / no longer pending)

            processed++;
            // After commit only — a rolled-back or retried deletion never sends it twice
            sendFinalWhatsApp(tenant.getId(), ownerPhones);
        }

        log.info("[AccountDeletionScheduler] Processed {} tenant deletion(s).", processed);
    }

    /**
     * Execute complete deletion for a single expired tenant, inside the caller's per-tenant
     * transaction.
     *
     * <p><b>Order is critical</b> (v1s-15-11 supersedes the 14-5 DN1 "DELETED before DROP" order,
     * which left a DELETED tenant with a lingering schema that the loop never retried):
     * <ol>
     *   <li>Lock the tenant row ({@code FOR UPDATE}) and skip it unless still DELETION_PENDING with an
     *       expired {@code deletion_scheduled_at}.</li>
     *   <li>Capture the OWNER phone numbers (memberships still exist).</li>
     *   <li>DROP schema CASCADE — own auto-commit connection, {@code IF EXISTS} so idempotent.
     *       If it throws, the transaction rolls back and nothing is persisted.</li>
     *   <li>Delete memberships, orphaned users, refresh tokens, device tokens (orphans only).</li>
     *   <li>Mark tenant DELETED — last, so DELETED always means "schema gone".</li>
     * </ol>
     * A failure after a successful DROP rolls back the public deletes: the tenant stays
     * DELETION_PENDING and the next run completes it ({@code DROP IF EXISTS} is then a no-op).
     *
     * @return the OWNER phone numbers to notify after commit, or {@code null} when skipped
     */
    private List<String> executeDeletionForTenant(UUID tenantId) {
        // 1. Row lock + re-check of status AND expiry (detects a cancellation — or a cancel then a
        //    new request with a future date — since findAll)
        List<Map<String, Object>> locked = jdbcTemplate.queryForList(LOCK_TENANT_SQL, tenantId);
        if (locked.isEmpty() || !isExpiredPending(locked.get(0), clock.instant())) {
            log.info("[AccountDeletionScheduler] Tenant {} no longer expired DELETION_PENDING (cancelled?), skipping.",
                    tenantId);
            return null;
        }
        Tenant fresh = tenantRepository.findById(tenantId).orElse(null);
        if (fresh == null) return null;
        String schemaName = fresh.getSchemaName();

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
        }

        // 9. Mark tenant DELETED — only once the schema is gone
        tenantRepository.save(fresh.withStatus(TenantStatus.DELETED, null));

        log.info("[AccountDeletionScheduler] Tenant {} ({}) fully deleted: {} users orphaned, schema dropped.",
                tenantId, schemaName, orphanedUserIds.size());
        return ownerPhones;
    }

    private static boolean isExpiredPending(Map<String, Object> row, Instant now) {
        if (!TenantStatus.DELETION_PENDING.name().equals(row.get("status"))) return false;
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
                whatsAppPort.sendReport(phone, FINAL_WHATSAPP_MESSAGE);
            } catch (Exception e) {
                // Non-blocking — the deletion is already committed
                log.error("[AccountDeletionScheduler] Failed to send final WhatsApp for tenant {}: {}",
                        tenantId, e.getMessage(), e);
            }
        }
    }
}
