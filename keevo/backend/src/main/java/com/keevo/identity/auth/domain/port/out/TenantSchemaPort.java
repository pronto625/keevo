package com.keevo.identity.auth.domain.port.out;

import java.util.UUID;

/**
 * TenantSchemaPort — Operations that must execute inside the isolated tenant schema.
 *
 * <p>Separate from the standard {@link UserRepository} and {@link TenantRepository} which
 * operate in the public schema. These operations target a specific {@code kv_xxxxxx} schema.
 *
 * <p>Architecture: domain port (no Spring/JDBC imports here — pure Java interface).
 */
public interface TenantSchemaPort {

    /**
     * Assign the OWNER role to the given user in the tenant schema.
     *
     * <p>Executes:
     * {@code INSERT INTO {schemaName}.user_roles (user_id, role_id)
     *         SELECT :userId, id FROM {schemaName}.roles WHERE name = 'OWNER'}
     *
     * <p>Called by RegistrationService AFTER the user is saved to public.users,
     * so the UUID is known.
     *
     * @param schemaName the tenant's PostgreSQL schema name (e.g., "kv_abc123")
     * @param userId     the UUID of the newly registered user
     */
    void assignOwnerRole(String schemaName, UUID userId);
}
