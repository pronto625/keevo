package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.6 — « shared account » policy (Specification): a member's account is shared when it holds a membership in
 * another company (any role, active or not) or is OWNER of this one. The account then belongs to the person, not to
 * this company: its owner (or manager) may not change its password or identifiers, and a deactivation only closes
 * this company's access.
 *
 * <p>Story 19.6b — recovery: an account that is OWNER here, has no membership elsewhere and no recovery channel of its
 * own (a username without phone nor verified email) is NOT shared: the owners of this company keep resetting its
 * password and identifiers, otherwise nobody could ever recover it. (Joining another company requires a recovery
 * channel, so an account shared with another company always has one.)
 *
 * <p>Story 19.6b — an account that joined this company by invitation is the person's own: it stays shared even after
 * she left every other company (this company never created it; it never manages its password or identifiers).
 *
 * <p>Complexity: one {@code EXISTS} on the {@code (user_id, tenant_id)} unique index, the membership of this company by
 * that same index, one {@code EXISTS} on the invitations' unique key, and the user by primary key only for an OWNER here without membership elsewhere.
 */
@Component
public class SharedAccountGuard {

    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipInvitationRepository invitationRepository;

    public SharedAccountGuard(UserMembershipRepository membershipRepository, TenantRepository tenantRepository,
                              UserRepository userRepository, MembershipInvitationRepository invitationRepository) {
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
    }

    /** Whether the account of {@code userId} is shared, seen from {@code tenantId}. */
    public boolean isShared(UUID userId, UUID tenantId) {
        return membershipRepository.hasMembershipOutside(userId, tenantId)
                || invitationRepository.hasAccepted(tenantId, userId)
                || isOwnerWithOwnRecovery(userId, membershipRepository.findByUserIdAndTenantId(userId, tenantId));
    }

    /**
     * Same rule when the caller already loaded the membership of this company ({@code here}, possibly empty): no
     * second lookup.
     */
    public boolean isShared(UUID userId, UUID tenantId, Optional<UserTenantMembership> here) {
        return membershipRepository.hasMembershipOutside(userId, tenantId)
                || invitationRepository.hasAccepted(tenantId, userId)
                || isOwnerWithOwnRecovery(userId, here);
    }

    /** OWNER here, and able to recover the account alone (Story 19.6b) — the user is read only for an OWNER. */
    private boolean isOwnerWithOwnRecovery(UUID userId, Optional<UserTenantMembership> here) {
        return isOwner(here) && userRepository.findById(userId).map(User::hasRecoveryChannel).orElse(true);
    }

    private static boolean isOwner(Optional<UserTenantMembership> membership) {
        return membership.map(UserTenantMembership::getRole).map("OWNER"::equals).orElse(false);
    }

    /**
     * Refuses an account-wide change (password, phone, username) on a shared account with
     * {@code MEMBER_ACCOUNT_SHARED}; the current company comes from {@link TenantContext}.
     */
    public void refuseIfShared(UUID userId) {
        if (isShared(userId, currentTenantId())) {
            throw new DomainException(ErrorCode.MEMBER_ACCOUNT_SHARED);
        }
    }

    private UUID currentTenantId() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null) {
            throw new IllegalStateException("TenantContext required to check a member's account");
        }
        return tenantRepository.findBySchemaName(schema)
                .map(Tenant::getId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
    }
}
