package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.identity.employee.domain.model.MembershipInvitation.IdentifierKind;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.MembershipInvitationUseCase;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.6b — invitations of existing accounts (Façade). An owner (or a manager, 19.5 rules) who adds the phone or
 * username of an existing account invites it ({@link #invite}, called by {@link CreateEmployeeService}); only the
 * person's acceptance creates the membership ({@link InvitationAcceptance}).
 *
 * <p>Privacy (decisions D2–D3 of the spec): the inviting company learns nothing about the account — the platform
 * administrator, an inactive account, a pending invitation and a second (or later) refusal younger than
 * {@link #DECLINE_COOLDOWN} all get the same neutral answer as a new invitation, without any message; at most one
 * message per invitation; at most
 * {@code keevo.invitations.daily-limit-per-tenant} invitations created or renewed per company per rolling 24 h.
 *
 * <p>Complexity: invite = one count on {@code (tenant_id, invited_at)} + one conditional upsert on the unique
 * {@code (tenant_id, user_id)}; lists = one indexed query each; accept/decline = see {@link InvitationAcceptance}.
 */
@Service
public class MembershipInvitationService implements MembershipInvitationUseCase {

    /** How long an invitation can be answered. */
    static final Duration TTL = Duration.ofDays(7);
    /**
     * After the {@link MembershipInvitation#DECLINES_BEFORE_COOLDOWN}-th refusal (reprise 11/11/2026: not after the
     * first one), the same company cannot re-invite the person (silently) for this long.
     */
    static final Duration DECLINE_COOLDOWN = Duration.ofDays(30);
    /** At most one invitation message per (company, person) in this window, whatever the re-additions/cancels. */
    static final Duration MESSAGE_COOLDOWN = Duration.ofDays(1);
    static final String DAILY_LIMIT_MESSAGE = "Trop d'invitations envoyées aujourd'hui : réessayez demain";

    private final MembershipInvitationRepository invitationRepository;
    private final TenantRepository tenantRepository;
    private final InvitationAcceptance acceptance;
    private final MembershipInvitationNotifier notifier;
    private final Clock clock;
    private final int dailyLimit;

    public MembershipInvitationService(MembershipInvitationRepository invitationRepository,
                                       TenantRepository tenantRepository, InvitationAcceptance acceptance,
                                       MembershipInvitationNotifier notifier, Clock clock,
                                       @Value("${keevo.invitations.daily-limit-per-tenant:20}") int dailyLimit) {
        this.invitationRepository = invitationRepository;
        this.tenantRepository = tenantRepository;
        this.acceptance = acceptance;
        this.notifier = notifier;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    /**
     * Invites {@code account} into {@code tenant} with what the owner typed. Runs in the caller's transaction (after
     * the plan check and the « already a member here » check). Neutral: returns normally whatever the account's state.
     *
     * @throws DomainException RATE_LIMIT_EXCEEDED when the company reached its daily invitations
     */
    public void invite(User account, Tenant tenant, CreateEmployeeCommand command, Role role, IdentifierKind kind,
                       String identifier) {
        Instant now = clock.instant();
        if (invitationRepository.countInvitedSince(tenant.getId(), now.minus(Duration.ofDays(1))) >= dailyLimit) {
            throw DomainException.userFacing(ErrorCode.RATE_LIMIT_EXCEEDED, DAILY_LIMIT_MESSAGE);
        }
        if (account.getRole() == Role.SUPER_ADMIN || !account.isActive()) {
            return; // never invitable — same answer, nothing stored, nothing sent
        }
        boolean due = invitationRepository.upsertPending(MembershipInvitation.pending(tenant.getId(), account.getId(),
                role.name(), command.storeId(), command.firstName(),
                command.lastName() == null ? "" : command.lastName(), kind, identifier,
                command.actorId(), now, now.plus(TTL)), now, now.minus(DECLINE_COOLDOWN),
                now.minus(MESSAGE_COOLDOWN));
        if (due) {
            notifier.invitationCreated(account, tenant.getName(), role.name());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipInvitation> listForCompany(UUID storeScope) {
        return invitationRepository.findPendingForTenant(currentTenantId(), storeScope, clock.instant());
    }

    @Override
    @Transactional
    public void cancel(UUID invitationId, UUID storeScope) {
        if (!invitationRepository.cancel(invitationId, currentTenantId(), storeScope, clock.instant())) {
            throw new DomainException(ErrorCode.INVITATION_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingInvitation> listForUser(UUID userId) {
        return invitationRepository.findPendingForUser(userId, clock.instant()).stream()
                .map(p -> new PendingInvitation(p.invitation().id(), p.tenantName(), p.invitation().role(),
                        p.invitation().invitedAt(), p.invitation().expiresAt()))
                .toList();
    }

    /**
     * Not transactional on purpose: the inviting company's tenant (one keyed lookup, schema name validated) is bound
     * first, then {@link InvitationAcceptance} opens the transaction in that schema; the caller's tenant (an open
     * session elsewhere) is restored afterwards. An invitation that can never be accepted is cancelled.
     */
    @Override
    public JoinedCompany accept(UUID invitationId, UUID userId) {
        String schema = TenantSchema.validate(invitationRepository.pendingTenantSchema(invitationId, userId,
                        clock.instant())
                .orElseThrow(() -> new DomainException(ErrorCode.INVITATION_NOT_FOUND)));
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schema);
        try {
            return acceptance.accept(invitationId, userId);
        } catch (InvitationAcceptance.StaleInvitationException stale) {
            acceptance.cancelStale(invitationId); // can never be accepted: no longer offered to the person
            throw stale.error();
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenant(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    @Override
    public void decline(UUID invitationId, UUID userId) {
        acceptance.decline(invitationId, userId);
    }

    private UUID currentTenantId() {
        return tenantRepository.findBySchemaName(TenantContext.getCurrentTenant())
                .map(Tenant::getId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
    }
}
