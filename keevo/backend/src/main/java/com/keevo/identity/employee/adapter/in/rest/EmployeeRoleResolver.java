package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.MembershipInvitation.IdentifierKind;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository;
import com.keevo.identity.role.domain.model.CustomRole;
import com.keevo.identity.role.domain.port.out.CustomRoleStore;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
    private final MembershipInvitationRepository invitationRepository;
    /** Story 19.14 — the members' owner-defined roles; {@code null} in unit tests (none shown). */
    private final CustomRoleStore customRoles;

    /** Without custom roles (unit tests). */
    public EmployeeRoleResolver(UserMembershipRepository membershipRepository,
                                 TenantRepository tenantRepository,
                                 UserRepository userRepository,
                                 MembershipInvitationRepository invitationRepository) {
        this(membershipRepository, tenantRepository, userRepository, invitationRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EmployeeRoleResolver(UserMembershipRepository membershipRepository,
                                 TenantRepository tenantRepository,
                                 UserRepository userRepository,
                                 MembershipInvitationRepository invitationRepository,
                                 CustomRoleStore customRoles) {
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.customRoles = customRoles;
    }

    /**
     * What the employee card and edit form show beside the aggregate (Story 14.11 AC5/AC6, Story 19.4).
     *
     * @param role        membership role in the current tenant, {@code "EMPLOYEE"} when none
     * @param phoneNumber E.164 phone; {@code ""} for a known account without phone (Story 19.1 — V1 clients never get
     *                    {@code null} for an existing account), {@code null} only when the user is unknown
     * @param username    username as typed, {@code null} when none or when it is the technical username of a social
     *                    account (never shown)
     * @param member      Story 19.5 — whether a membership row exists in the current tenant ({@code role} is then
     *                    the real one, not the {@code "EMPLOYEE"} default); team scoping refuses non-members
     */
    public record Enrichment(String role, String phoneNumber, String username, boolean member,
                             CustomRole.Assigned customRole) {

        /** Without custom role (Story 19.5 shape). */
        public Enrichment(String role, String phoneNumber, String username, boolean member) {
            this(role, phoneNumber, username, member, null);
        }

        /** A member's enrichment (tests, callers that do not scope). */
        public Enrichment(String role, String phoneNumber, String username) {
            this(role, phoneNumber, username, true, null);
        }
    }

    /**
     * Story 19.4 — enrichment of several employees in three queries whatever their number (tenant once, users by
     * {@code id = ANY(?)}, memberships of the tenant for those users), instead of three per employee.
     *
     * <p>Story 19.6b — a member who joined by invitation shows the company only the identifier it typed (one more set
     * query on the accepted invitations): invited by username → phone {@code ""}; invited by phone → no username.
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
        Map<UUID, IdentifierKind> invitedBy = invitationRepository.acceptedIdentifierKinds(tenant.getId(), userIds);
        // Story 19.14 — one set query for the owner-defined roles of the page's members
        Map<UUID, CustomRole.Assigned> custom = customRoles == null ? Map.of()
                : customRoles.findAssigned(tenant.getId(), userIds);
        Map<UUID, Enrichment> result = new HashMap<>();
        for (UUID userId : userIds) {
            User user = users.get(userId);
            IdentifierKind kind = invitedBy.get(userId);
            boolean phoneVisible = kind != IdentifierKind.USERNAME;
            boolean usernameVisible = kind != IdentifierKind.PHONE;
            result.put(userId, new Enrichment(
                    roles.getOrDefault(userId, "EMPLOYEE"),
                    user == null ? null
                            : (phoneVisible && user.getPhoneNumber() != null ? user.getPhoneNumber() : ""),
                    user == null || !usernameVisible || Usernames.isTechnical(user.getUsername())
                            ? null : user.getUsername(),
                    roles.containsKey(userId),
                    custom.get(userId)));
        }
        return result;
    }

    /**
     * Story 19.5 — the membership role of a user in the current tenant, empty when there is no membership (no
     * default: team scoping must fail closed).
     */
    public Optional<String> membershipRole(UUID userId) {
        return membership(userId).map(UserTenantMembership::getRole);
    }

    /** Story 19.5b — the membership of a user in the current tenant (role, active, deactivated-by-manager flag). */
    public Optional<UserTenantMembership> membership(UUID userId) {
        var tenant = tenantRepository.findBySchemaName(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        return membershipRepository.findByUserIdAndTenantId(userId, tenant.getId());
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
