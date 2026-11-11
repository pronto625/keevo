package com.keevo.identity.role.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 19.14 — a role defined by the owner of a company ({@code public.tenant_roles}).
 *
 * @param id          role id
 * @param tenantId    the company ({@code public.tenants.id})
 * @param name        the name the owner typed (« Caissier »), unique per company case-insensitively
 * @param baseRole    the built-in role whose baseline behaviour it keeps ({@code MANAGER}, {@code EMPLOYEE},
 *                    {@code VIEWER}); immutable
 * @param permissions the module permissions it grants (replacing the built-in role's)
 * @param storeIds    the stores it applies to; {@code null} = every store
 * @param createdAt   creation time
 * @param updatedAt   last change
 */
public record CustomRole(UUID id, UUID tenantId, String name, String baseRole, Set<String> permissions,
                         List<UUID> storeIds, Instant createdAt, Instant updatedAt) {

    public CustomRole {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        storeIds = storeIds == null ? null : List.copyOf(storeIds);
    }

    /** Whether a member assigned to {@code storeId} may hold this role. */
    public boolean appliesToStore(UUID storeId) {
        return storeIds == null || (storeId != null && storeIds.contains(storeId));
    }

    /** A role with its number of members (list screen). */
    public record WithMembers(CustomRole role, int memberCount) {
    }

    /** The custom role of one member, as the team screens show it. */
    public record Assigned(UUID roleId, String name, Set<String> permissions) {

        public Assigned {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
}
