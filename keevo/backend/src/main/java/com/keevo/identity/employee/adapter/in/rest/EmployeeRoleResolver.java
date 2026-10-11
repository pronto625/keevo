package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
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
    private final UserRepository userRepository;

    public EmployeeRoleResolver(UserMembershipRepository membershipRepository,
                                 TenantRepository tenantRepository,
                                 UserRepository userRepository) {
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    /**
     * Resolve the phone number of a given user ({@code public.users}), so the
     * employee edit form can be pre-filled (Story 14.11, AC6).
     *
     * <p>One lookup per employee, like {@link #resolveRole(UUID)}: lists are
     * bounded by {@code PlanLimitGuard} and the port offers no bulk finder.
     *
     * @return the E.164 phone number, or {@code null} if the user is unknown
     */
    public String resolvePhone(UUID userId) {
        return userRepository.findById(userId)
                .map(User::getPhoneNumber)
                .orElse(null);
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
