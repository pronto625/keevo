package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeCreatedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.identity.employee.domain.port.in.MembershipInvitationUseCase.JoinedCompany;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Story 19.6b — the person's answer to an invitation, one transaction each (Unit of Work). {@link #accept} runs in the
 * inviting company's schema: the caller ({@link MembershipInvitationService}) binds that tenant BEFORE the transaction
 * opens (the connection's search_path is set when it is acquired).
 *
 * <p>Acceptance: per-company lock of member arrivals FIRST (the same lock order as an invitation: lock, then the
 * invitation row — no deadlock with a re-invitation) → invitation row locked → the account (active, not the platform
 * administrator) → a recovery channel of its own → already a member: active = consumed (idempotent answer), inactive
 * = {@link StaleInvitationException} → the invited store still active, else stale → the plan's employee ceiling →
 * membership with the invited role + employee row (names and store typed by the owner, no forced password change) +
 * {@code EmployeeCreatedEvent} → invitation ACCEPTED → the inviter's inbox after commit. Password and global role are
 * never touched. Complexity: advisory lock, invitation by primary key (locked), user by primary key, membership by
 * unique key, store by primary key, one count, two inserts, one update.
 */
@Component
public class InvitationAcceptance {

    static final String PLAN_FULL_MESSAGE = "Cette entreprise a atteint la limite de membres de son plan : son "
            + "responsable doit changer de plan avant que vous puissiez la rejoindre";
    static final String STORE_GONE_MESSAGE = "La boutique prévue pour vous n'existe plus : demandez une nouvelle "
            + "invitation";
    static final String INACTIVE_MESSAGE = "Vous avez été désactivé dans cette entreprise : demandez à son "
            + "responsable de vous réactiver";

    /**
     * The invitation can never be accepted any more (deactivated member, store gone): the caller cancels it in its own
     * transaction, then rethrows.
     */
    public static final class StaleInvitationException extends RuntimeException {
        private final transient DomainException error;

        StaleInvitationException(ErrorCode code, String userMessage) {
            super(code.name(), null, false, false);
            this.error = DomainException.userFacing(code, userMessage);
        }

        /** The error the person receives once the invitation is cancelled. */
        public DomainException error() {
            return error;
        }
    }

    private final MembershipInvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeePlanLimit employeePlanLimit;
    private final StoreQueryPort storeQuery;
    private final ApplicationEventPublisher eventPublisher;
    private final MembershipInvitationNotifier notifier;
    private final Clock clock;

    public InvitationAcceptance(MembershipInvitationRepository invitationRepository, UserRepository userRepository,
                                UserMembershipRepository membershipRepository, EmployeeRepository employeeRepository,
                                EmployeePlanLimit employeePlanLimit, StoreQueryPort storeQuery,
                                ApplicationEventPublisher eventPublisher, MembershipInvitationNotifier notifier,
                                Clock clock) {
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.employeeRepository = employeeRepository;
        this.employeePlanLimit = employeePlanLimit;
        this.storeQuery = storeQuery;
        this.eventPublisher = eventPublisher;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Transactional
    public JoinedCompany accept(UUID invitationId, UUID userId) {
        Instant now = clock.instant();
        employeePlanLimit.lock(); // first, like CreateEmployeeService: one lock order
        var pending = invitationRepository.lockPendingForUser(invitationId, userId, now)
                .orElseThrow(() -> new DomainException(ErrorCode.INVITATION_NOT_FOUND));
        String schema = pending.tenantSchema();
        if (!schema.equals(TenantContext.getCurrentTenant())) {
            throw new IllegalStateException("acceptance must run in the inviting company's tenant context");
        }
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .filter(u -> u.getRole() != Role.SUPER_ADMIN)
                .orElseThrow(() -> new DomainException(ErrorCode.INVITATION_NOT_FOUND));
        if (!user.hasRecoveryChannel()) {
            throw new DomainException(ErrorCode.ACCOUNT_RECOVERY_REQUIRED);
        }
        MembershipInvitation invitation = pending.invitation();
        JoinedCompany joined = new JoinedCompany(pending.tenantCode(), pending.tenantName(), invitation.role());
        var membership = membershipRepository.findByUserIdAndTenantId(userId, invitation.tenantId());
        if (membership.isPresent()) {
            if (!membership.get().isActive()) {
                throw new StaleInvitationException(ErrorCode.MEMBER_INACTIVE_IN_COMPANY, INACTIVE_MESSAGE);
            }
            // already a member (joined meanwhile): the invitation is consumed, the answer is the company
            invitationRepository.markResponded(invitationId, MembershipInvitation.Status.ACCEPTED, now);
            return new JoinedCompany(pending.tenantCode(), pending.tenantName(), membership.get().getRole());
        }
        if (storeQuery.findById(schema, invitation.storeId()).filter(StoreView::active).isEmpty()) {
            throw new StaleInvitationException(ErrorCode.INVITATION_NOT_FOUND, STORE_GONE_MESSAGE);
        }
        try {
            employeePlanLimit.check(invitation.storeId());
        } catch (DomainException e) {
            if (e.getErrorCode() == ErrorCode.PLAN_LIMIT_EXCEEDED) {
                // the person sees neither the company's plan nor its usage; the invitation stays pending
                throw DomainException.userFacing(ErrorCode.PLAN_LIMIT_EXCEEDED, PLAN_FULL_MESSAGE);
            }
            throw e;
        }
        membershipRepository.save(UserTenantMembership.create(userId, invitation.tenantId(), invitation.role()));
        Employee employee = employeeRepository.save(Employee.createForExistingAccount(
                userId, invitation.storeId(), invitation.firstName(), invitation.lastName()));
        eventPublisher.publishEvent(new EmployeeCreatedEvent(
                userId, userId, employee.getId(), invitation.storeId(), now));
        invitationRepository.markResponded(invitationId, MembershipInvitation.Status.ACCEPTED, now);
        // the inviter's inbox, only while he is an active member of the company (never a support account)
        UUID inviter = invitation.invitedBy();
        if (inviter != null && membershipRepository.findByUserIdAndTenantId(inviter, invitation.tenantId())
                .map(UserTenantMembership::isActive).orElse(false)) {
            notifier.invitationAccepted(schema, inviter, invitation.displayName());
        }
        return joined;
    }

    @Transactional
    public void decline(UUID invitationId, UUID userId) {
        Instant now = clock.instant();
        invitationRepository.lockPendingForUser(invitationId, userId, now)
                .orElseThrow(() -> new DomainException(ErrorCode.INVITATION_NOT_FOUND));
        invitationRepository.markResponded(invitationId, MembershipInvitation.Status.DECLINED, now);
    }

    /** Cancels an invitation that can never be accepted (its own transaction: the failed acceptance rolled back). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelStale(UUID invitationId) {
        invitationRepository.markResponded(invitationId, MembershipInvitation.Status.CANCELLED, clock.instant());
    }
}
