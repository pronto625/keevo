package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
     * What the employee card and edit form show beside the aggregate (Story 14.11 AC5/AC6, Story 19.4).
     *
     * @param role        membership role in the current tenant, {@code "EMPLOYEE"} when none
     * @param phoneNumber E.164 phone; {@code ""} for a known account without phone (Story 19.1 — V1 clients never get
     *                    {@code null} for an existing account), {@code null} only when the user is unknown
     * @param username    username as typed, {@code null} when none or when it is the technical username of a social
     *                    account (never shown)
     */
    public record Enrichment(String role, String phoneNumber, String username) {
    }

    /**
     * Story 19.4 — enrichment of several employees in three queries whatever their number (tenant once, users by
     * {@code id = ANY(?)}, memberships of the tenant for those users), instead of three per employee.
     *
     * @return one entry per requested user id
     */
    public Map<UUID, Enrichment> resolveAll(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        var tenant = tenantRepository.findBySchemaName(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        Map<UUID, User> users = userRepository.findAllByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        Map<UUID, String> roles = membershipRepository.findByTenantIdAndUserIdIn(tenant.getId(), userIds).stream()
                .collect(Collectors.toMap(UserTenantMembership::getUserId, UserTenantMembership::getRole,
                        (a, b) -> a));
        Map<UUID, Enrichment> result = new HashMap<>();
        for (UUID userId : userIds) {
            User user = users.get(userId);
            result.put(userId, new Enrichment(
                    roles.getOrDefault(userId, "EMPLOYEE"),
                    user == null ? null : (user.getPhoneNumber() != null ? user.getPhoneNumber() : ""),
                    user == null || Usernames.isTechnical(user.getUsername()) ? null : user.getUsername()));
        }
        return result;
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
