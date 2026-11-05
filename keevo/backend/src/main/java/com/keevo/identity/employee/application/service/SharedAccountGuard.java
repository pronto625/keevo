package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
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
 * <p>Complexity: one {@code EXISTS} on the {@code (user_id, tenant_id)} unique index, plus the membership of this
 * company by that same index only when the first answers false.
 */
@Component
public class SharedAccountGuard {

    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;

    public SharedAccountGuard(UserMembershipRepository membershipRepository, TenantRepository tenantRepository) {
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
    }

    /** Whether the account of {@code userId} is shared, seen from {@code tenantId}. */
    public boolean isShared(UUID userId, UUID tenantId) {
        return membershipRepository.hasMembershipOutside(userId, tenantId)
                || isOwner(membershipRepository.findByUserIdAndTenantId(userId, tenantId));
    }

    /**
     * Same rule when the caller already loaded the membership of this company ({@code here}, possibly empty): no
     * second lookup, the {@code EXISTS} runs only when the account is not OWNER here.
     */
    public boolean isShared(UUID userId, UUID tenantId, Optional<UserTenantMembership> here) {
        return isOwner(here) || membershipRepository.hasMembershipOutside(userId, tenantId);
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
