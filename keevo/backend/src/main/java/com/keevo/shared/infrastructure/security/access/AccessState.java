package com.keevo.shared.infrastructure.security.access;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 16.11 — the server-side « access state » of a user in one account, read by
 * {@link com.keevo.shared.infrastructure.security.JwtAuthFilter} on every request (through
 * {@link AccessStateService}). The access token proves identity only; every right comes from here.
 *
 * @param role                   membership role ({@code OWNER}, {@code EMPLOYEE}, {@code SUPER_ADMIN})
 * @param membershipActive       membership {@code is_active}
 * @param tenantStatus           account status ({@code ACTIVE}, {@code SUSPENDED}, {@code DELETION_PENDING}, ...)
 * @param tokensValidAfter       revocation cutoff ({@code tokens_valid_after}), null when never revoked
 * @param employee               the employee row of the account (null when the user has none)
 * @param openSessionIds         device sessions that still hold a live refresh token
 * @param customRole             Story 19.14 — the owner-defined role of the membership, null when none
 */
public record AccessState(
        String role,
        boolean membershipActive,
        String tenantStatus,
        Instant tokensValidAfter,
        EmployeeState employee,
        Set<UUID> openSessionIds,
        CustomRoleState customRole
) {

    /**
     * Employee row of the account schema.
     *
     * @param storeId                assigned store
     * @param status                 {@code ACTIVE} / {@code INACTIVE}
     * @param passwordChangeRequired forced password change pending
     * @param firstName              first name (actor label in notifications and audit)
     */
    public record EmployeeState(UUID storeId, String status, boolean passwordChangeRequired, String firstName) {
    }

    /**
     * Story 19.14 — the custom role row ({@code public.tenant_roles}) of the membership.
     *
     * @param id          role id
     * @param name        role name
     * @param baseRole    the built-in role it is based on ({@code MANAGER}, {@code EMPLOYEE}, {@code VIEWER})
     * @param permissions the chosen permissions
     * @param storeIds    the stores it applies to, {@code null} = every store
     */
    public record CustomRoleState(UUID id, String name, String baseRole, Set<String> permissions, List<UUID> storeIds) {

        public CustomRoleState {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
            storeIds = storeIds == null ? null : List.copyOf(storeIds);
        }

        /**
         * The permissions the role grants a member with this membership role and store: the chosen ones, or none
         * (fail closed) when the base role differs from the membership role or the store is outside the role's list.
         */
        public Set<String> permissionsFor(String membershipRole, UUID storeId) {
            if (baseRole == null || !baseRole.equals(membershipRole)) {
                return Set.of();
            }
            if (storeIds != null && (storeId == null || !storeIds.contains(storeId))) {
                return Set.of();
            }
            return permissions;
        }
    }

    public AccessState {
        openSessionIds = openSessionIds == null ? Set.of() : Set.copyOf(openSessionIds);
    }

    /** Before Story 19.14 — no custom role. */
    public AccessState(String role, boolean membershipActive, String tenantStatus, Instant tokensValidAfter,
                       EmployeeState employee, Set<UUID> openSessionIds) {
        this(role, membershipActive, tenantStatus, tokensValidAfter, employee, openSessionIds, null);
    }

    public boolean isSessionOpen(UUID sessionId) {
        return openSessionIds.contains(sessionId);
    }
}
