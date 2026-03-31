package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UserRepository — Driven port for user persistence.
 *
 * <p>Interface — implemented in the persistence adapter layer.
 * Operations target the PUBLIC schema (global user registry).
 */
public interface UserRepository {

    /** Persist a new user. Returns the saved user (with generated ID if any). */
    User save(User user);

    /** Find user by ID. */
    Optional<User> findById(UUID id);

    /** Find user by phone number. Returns empty if not found. */
    Optional<User> findByPhoneNumber(String phoneNumber);

    /** Check if a phone number is already registered (for fast uniqueness check). */
    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Story 7.2 — Find the OWNER user for a given tenant schema name.
     * Used for resolving WhatsApp delivery phone number.
     *
     * <p>Queries public schema: user_tenant_memberships JOIN users WHERE role='OWNER'.
     *
     * @param schemaName tenant schema name (e.g., "kv_abc123")
     * @return the OWNER User, or empty if not found
     */
    Optional<User> findOwnerByTenantSchemaName(String schemaName);

    /**
     * Story 1.7 — Two-step login: load all active memberships for a user together with
     * tenant metadata (code, name, schemaName) for the login session response.
     *
     * <p>Implemented via JdbcTemplate (NOT Hibernate) to avoid TenantContext dependency:
     * joins public.user_tenant_memberships + public.tenants in one schema-qualified query.
     *
     * @param userId the user's UUID
     * @return list of membership projections, empty if none
     */
    List<UserMembershipInfo> findMembershipsWithTenantInfo(UUID userId);
}
