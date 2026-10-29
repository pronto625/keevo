package com.keevo.admin.subscription.application.service;

import com.keevo.admin.subscription.application.service.TargetTenantLookup.TargetTenant;
import com.keevo.admin.subscription.domain.model.TenantReactivatedEvent;
import com.keevo.admin.subscription.domain.model.TenantSuspendedEvent;
import com.keevo.admin.subscription.domain.port.in.ReactivateTenantUseCase;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantCommand;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantUseCase;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * AdminTenantSuspensionService — super-admin suspension / reactivation of a tenant (Story 9-2).
 *
 * <p>Suspension is the read-only lockout already enforced by {@code JwtAuthFilter} from the JWT
 * {@code tenantStatus} claim (writes → 403 {@code ACCOUNT_SUSPENDED}) and shown by the Flutter
 * {@code SuspensionBanner}. Status transitions are conditional UPDATEs (ACTIVE → SUSPENDED,
 * SUSPENDED → ACTIVE); any other state answers 409 {@code INVALID_STATE}. Then every membership
 * of the tenant gets its sessions revoked, so the next token refresh carries the new status.
 * The suspension date, reason and actor are kept on {@code public.tenants} until reactivation; the
 * owner is told on WhatsApp after commit ({@code TenantStatusWhatsAppNotificationListener}).
 */
@Service
public class AdminTenantSuspensionService implements SuspendTenantUseCase, ReactivateTenantUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantSuspensionService.class);

    static final int REASON_MIN = 3;
    static final int REASON_MAX = 500;

    /** Also keeps a lasting trace of the suspension (who, when, why), shown in the admin drawer. */
    static final String SUSPEND_SQL =
            "UPDATE public.tenants SET status = 'SUSPENDED', updated_at = NOW(), "
            + "suspended_at = ?, suspended_reason = ?, suspended_by = ? "
            + "WHERE id = ? AND status = 'ACTIVE'";
    /** Clears the suspension trace. */
    static final String REACTIVATE_SQL =
            "UPDATE public.tenants SET status = 'ACTIVE', updated_at = NOW(), "
            + "suspended_at = NULL, suspended_reason = NULL, suspended_by = NULL "
            + "WHERE id = ? AND status = 'SUSPENDED'";
    static final String MEMBERS_SQL =
            "SELECT DISTINCT user_id FROM public.user_tenant_memberships WHERE tenant_id = ?";

    private final TargetTenantLookup tenantLookup;
    private final JdbcTemplate jdbc;
    private final TokenRevocationPort tokenRevocationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AdminTenantSuspensionService(TargetTenantLookup tenantLookup, JdbcTemplate jdbc,
                                        TokenRevocationPort tokenRevocationPort,
                                        ApplicationEventPublisher eventPublisher, Clock clock) {
        this.tenantLookup = tenantLookup;
        this.jdbc = jdbc;
        this.tokenRevocationPort = tokenRevocationPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void suspend(SuspendTenantCommand cmd) {
        String reason = cmd.reason() == null ? "" : cmd.reason().trim();
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            throw AdminPlanService.validation("reason", "Motif requis (3 à 500 caractères)");
        }
        TargetTenant tenant = tenantLookup.load(cmd.tenantId());
        java.time.Instant now = clock.instant();
        if (jdbc.update(SUSPEND_SQL, java.sql.Timestamp.from(now), reason, cmd.actorId(), tenant.id()) == 0) {
            throw new DomainException(ErrorCode.INVALID_STATE,
                    "Tenant " + tenant.id() + " cannot be suspended from status " + tenant.status());
        }
        int revoked = revokeAllMemberSessions(tenant);
        log.info("[ADMIN AUDIT] Tenant suspended: actor={} tenant={} sessionsRevoked={} reason={}",
                cmd.actorId(), tenant.id(), revoked, reason);
        eventPublisher.publishEvent(new TenantSuspendedEvent(cmd.actorId(), tenant.id(), tenant.schemaName(),
                reason, now));
    }

    @Override
    @Transactional
    public void reactivate(UUID actorId, UUID tenantId) {
        TargetTenant tenant = tenantLookup.load(tenantId);
        if (jdbc.update(REACTIVATE_SQL, tenant.id()) == 0) {
            throw new DomainException(ErrorCode.INVALID_STATE,
                    "Tenant " + tenant.id() + " cannot be reactivated from status " + tenant.status());
        }
        int revoked = revokeAllMemberSessions(tenant);
        log.info("[ADMIN AUDIT] Tenant reactivated: actor={} tenant={} sessionsRevoked={}",
                actorId, tenant.id(), revoked);
        eventPublisher.publishEvent(new TenantReactivatedEvent(actorId, tenant.id(), tenant.schemaName(),
                clock.instant()));
    }

    private int revokeAllMemberSessions(TargetTenant tenant) {
        List<UUID> members = jdbc.query(MEMBERS_SQL,
                (rs, i) -> UUID.fromString(rs.getString("user_id")), tenant.id());
        members.forEach(userId -> tokenRevocationPort.revokeAllSessions(userId, tenant.schemaName()));
        return members.size();
    }
}
