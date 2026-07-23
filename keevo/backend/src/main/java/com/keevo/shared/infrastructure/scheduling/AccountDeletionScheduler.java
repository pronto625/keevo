package com.keevo.shared.infrastructure.scheduling;

import com.keevo.identity.auth.domain.model.*;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AccountDeletionScheduler — daily job that executes the deletion of tenants
 * whose 30-day grace period has expired (Story 14.5, FR91).
 *
 * <p>Runs daily at 03:00 server time (offset from SubscriptionExpiryScheduler at 02:00).
 * Uses double constructor injection (real {@link Clock} + test {@link Clock}).
 * Mirror of {@link SubscriptionExpiryScheduler}.
 */
@Component
public class AccountDeletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionScheduler.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final WhatsAppPort whatsAppPort;
    private final TenantSchemaProvisioner schemaProvisioner;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** Production constructor — system clock. */
    @org.springframework.beans.factory.annotation.Autowired
    public AccountDeletionScheduler(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppPort whatsAppPort,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate) {
        this(tenantRepository, userRepository, whatsAppPort, schemaProvisioner,
             jdbcTemplate, Clock.systemUTC());
    }

    /** Test constructor — injectable clock. */
    public AccountDeletionScheduler(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     WhatsAppPort whatsAppPort,
                                     TenantSchemaProvisioner schemaProvisioner,
                                     JdbcTemplate jdbcTemplate,
                                     Clock clock) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.whatsAppPort = whatsAppPort;
        this.schemaProvisioner = schemaProvisioner;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Runs daily at 03:00 — processes all tenants whose grace period has expired.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void executeDeletions() {
        log.info("[AccountDeletionScheduler] Starting daily deletion check...");
        Instant now = clock.instant();

        List<Tenant> allTenants = tenantRepository.findAll();
        int processed = 0;

        for (Tenant tenant : allTenants) {
            if (tenant.getStatus() != TenantStatus.DELETION_PENDING) continue;

            Instant deletionScheduledAt = tenant.getDeletionScheduledAt();
            if (deletionScheduledAt == null || deletionScheduledAt.isAfter(now)) continue;

            try {
                executeDeletionForTenant(tenant);
                processed++;
            } catch (Exception e) {
                log.error("[AccountDeletionScheduler] Failed to delete tenant {} ({}): {}",
                        tenant.getId(), tenant.getSchemaName(), e.getMessage(), e);
                // Do not mark DELETED on failure — data must be preserved
            }
        }

        log.info("[AccountDeletionScheduler] Processed {} tenant deletion(s).", processed);
    }

    /**
     * Execute complete deletion for a single expired tenant.
     *
     * <p><b>Order is critical</b> (see Dev Notes in story spec):
     * <ol>
     *   <li>Send final WhatsApp</li>
     *   <li>Capture affected user IDs</li>
     *   <li>Delete memberships</li>
     *   <li>Delete orphaned users</li>
     *   <li>Delete refresh tokens</li>
     *   <li>Delete device tokens (orphaned users only)</li>
     *   <li>Mark tenant DELETED</li>
     *   <li>DROP schema CASCADE</li>
     * </ol>
     */
    private void executeDeletionForTenant(Tenant tenant) {
        // Re-fetch tenant to detect if deletion was cancelled during scheduler execution
        Tenant fresh = tenantRepository.findById(tenant.getId()).orElse(null);
        if (fresh == null || fresh.getStatus() != TenantStatus.DELETION_PENDING) {
            log.info("[AccountDeletionScheduler] Tenant {} no longer DELETION_PENDING (cancelled?), skipping.",
                    tenant.getId());
            return;
        }

        UUID tenantId = fresh.getId();
        String schemaName = fresh.getSchemaName();

        // 1. Send final WhatsApp to OWNER
        sendFinalWhatsApp(fresh);

        // 2. Capture affected user IDs BEFORE any deletion
        List<UUID> affectedUserIds = jdbcTemplate.queryForList(
                "SELECT user_id FROM public.user_tenant_memberships WHERE tenant_id = ?",
                UUID.class, tenantId);

        // 3. Delete memberships for this tenant
        jdbcTemplate.update("DELETE FROM public.user_tenant_memberships WHERE tenant_id = ?",
                (Object) tenantId);

        // 4. Delete ONLY orphaned users (not multi-tenant users)
        List<UUID> orphanedUserIds = new ArrayList<>();
        if (!affectedUserIds.isEmpty()) {
            List<UUID> deletedIds = jdbcTemplate.queryForList(
                    "DELETE FROM public.users u " +
                    "WHERE u.id = ANY(?::uuid[]) " +
                    "AND NOT EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.user_id = u.id) " +
                    "RETURNING u.id",
                    UUID.class, affectedUserIds.toArray(new UUID[0]));
            orphanedUserIds.addAll(deletedIds);
        }

        // 5. Delete refresh tokens for this tenant
        jdbcTemplate.update("DELETE FROM public.refresh_tokens WHERE tenant_id = ?",
                (Object) schemaName);

        // 6. Delete device tokens for orphaned users only
        if (!orphanedUserIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM public.device_tokens WHERE user_id = ANY(?::uuid[])",
                    (Object) orphanedUserIds.toArray(new UUID[0]));
        }

        // 7. Mark tenant DELETED (before DROP — if DROP fails, tenant is marked DELETED and retry is possible)
        Tenant deleted = fresh.withStatus(TenantStatus.DELETED, null);
        tenantRepository.save(deleted);

        // 8. DROP schema CASCADE (non-swallowing — propagate on failure)
        schemaProvisioner.dropSchemaForDeletion(schemaName);

        log.info("[AccountDeletionScheduler] Tenant {} ({}) fully deleted: {} users orphaned, schema dropped.",
                tenantId, schemaName, orphanedUserIds.size());
    }

    private void sendFinalWhatsApp(Tenant tenant) {
        try {
            // Find OWNER via user_tenant_memberships (only OWNERs in the tenant)
            List<UUID> ownerUserIds = jdbcTemplate.queryForList(
                    "SELECT user_id FROM public.user_tenant_memberships WHERE tenant_id = ? AND role = 'OWNER'",
                    UUID.class, tenant.getId());

            for (UUID ownerId : ownerUserIds) {
                userRepository.findById(ownerId).ifPresent(owner -> {
                    String message = "Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo.";
                    whatsAppPort.sendReport(owner.getPhoneNumber(), message);
                });
            }
        } catch (Exception e) {
            log.error("[AccountDeletionScheduler] Failed to send final WhatsApp for tenant {}: {}",
                    tenant.getId(), e.getMessage(), e);
            // Non-blocking — do not abort deletion for WhatsApp failure
        }
    }
}
