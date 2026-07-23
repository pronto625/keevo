package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * EmployeeRoleResolver — Helper component that resolves an employee's role
 * from {@code public.user_tenant_memberships} for DTO enrichment.
 *
 * <p>Not a hexagonal port — this is a mapping helper for the REST adapter layer.
 * Injected into {@link EmployeeController} so that {@code EmployeeResponseDto.fromDomain()}
 * can include the role without every existing use case having to change its return type.
 *
 * <p>Story 14.11 — AC5.
 */
@Component
public class EmployeeRoleResolver {

    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;

    public EmployeeRoleResolver(UserMembershipRepository membershipRepository,
                                 TenantRepository tenantRepository) {
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Resolve the role for a given user within the current tenant.
     *
     * @param userId the global user ID
     * @return the role string (e.g. "OWNER" or "EMPLOYEE"), defaults to "EMPLOYEE" if not found
     */
    public String resolveRole(UUID userId) {
        String tenantSchema = TenantContext.getCurrentTenant();
        var tenant = tenantRepository.findBySchemaName(tenantSchema)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        return membershipRepository.findByUserIdAndTenantId(userId, tenant.getId())
                .map(UserTenantMembership::getRole)
                .orElse("EMPLOYEE");
    }
}
