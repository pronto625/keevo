package com.keevo.identity.role.domain.port.out;

import com.keevo.identity.role.domain.model.CustomRole;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.14 — persistence of the owner-defined roles ({@code public.tenant_roles}) and of their assignment to
 * memberships ({@code public.user_tenant_memberships.custom_role_id}). Every method is scoped by the company id.
 */
public interface CustomRoleStore {

    /** The company's roles with their member counts, by name (one query). */
    List<CustomRole.WithMembers> findAll(UUID tenantId);

    Optional<CustomRole> findById(UUID tenantId, UUID roleId);

    /** Whether another role of the company ({@code excludeId} aside) has this name, case-insensitively. */
    boolean nameTaken(UUID tenantId, String name, UUID excludeId);

    int count(UUID tenantId);

    void insert(CustomRole role, UUID createdBy);

    /** Updates name, permissions and stores (the base role never changes). */
    void update(CustomRole role);

    void delete(UUID tenantId, UUID roleId);

    /** Number of memberships holding the role. */
    int memberCount(UUID tenantId, UUID roleId);

    /** Sets ({@code roleId}) or clears ({@code null}) the custom role of a membership. */
    void assign(UUID tenantId, UUID userId, UUID roleId);

    /** The custom role of a member, empty when he holds none. */
    Optional<CustomRole.Assigned> findAssigned(UUID tenantId, UUID userId);

    /** The custom roles of several members, keyed by user id (members without one are absent). One query. */
    Map<UUID, CustomRole.Assigned> findAssigned(UUID tenantId, Collection<UUID> userIds);
}
