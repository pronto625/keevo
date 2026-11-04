package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.api.OwnerRecipientResolver;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * Story 19.4 — the users with these ids in one query ({@code id = ANY(?)}); unknown ids are absent from the result,
     * an empty collection answers an empty list without a query.
     */
    List<User> findAllByIds(Collection<UUID> ids);

    /** Find user by phone number. Returns empty if not found. */
    Optional<User> findByPhoneNumber(String phoneNumber);

    /**
     * Story 19.2 — find a user by email, case-insensitively ({@code lower(email) = lower(?)} on
     * {@code uq_users_email_lower}). Empty for a null or blank email.
     */
    Optional<User> findByEmail(String email);

    /**
     * Story 19.4 — find a user by username, stripped and case-insensitively ({@code lower(username) = lower(?)} on
     * {@code uq_users_username_lower}). Empty for a null or blank username.
     */
    Optional<User> findByUsername(String username);

    /**
     * Story 19.2 — inserts the user unless a unique identifier (phone, email, username) is already taken
     * ({@code ON CONFLICT DO NOTHING}: a concurrent registration never aborts the caller's transaction).
     *
     * @return true when the row was inserted
     */
    boolean insertIfAbsent(User user);

    /**
     * Story 19.3 — gives the account the email, verified at {@code verifiedAt}, only if it has none and no other account
     * holds it case-insensitively: one conditional {@code UPDATE} (no read-then-write, no JPA merge).
     *
     * @return rows updated (0 when the account already has an email or the email is taken)
     */
    int setEmailIfAbsent(UUID id, String email, Instant verifiedAt);

    /**
     * Story 19.2 — locks the user row ({@code FOR UPDATE}) until the transaction ends, so two concurrent
     * company creations of the same user run one after the other.
     *
     * @return false when the user does not exist
     */
    boolean lockById(UUID id);

    /** Check if a phone number is already registered (for fast uniqueness check). */
    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Story 7.2 / v1s-14-17 — Find the primary active OWNER user for a given tenant schema name.
     *
     * <p>Primary owner = active OWNER membership ({@code m.is_active}) of an active user
     * ({@code u.is_active}) with the oldest membership {@code created_at}, tie-break on user id —
     * deterministic when several owners exist.
     *
     * <p>Do not call this to pick a WhatsApp recipient: use
     * {@link OwnerRecipientResolver#resolveOwnerWhatsAppPhone(String)}, which applies the
     * normalisation / format rules (enforced by {@code OwnerRecipientResolutionArchTest}).
     *
     * @param schemaName tenant schema name (e.g., "kv_abc123")
     * @return the primary active OWNER User, or empty if none
     */
    Optional<User> findOwnerByTenantSchemaName(String schemaName);

    /**
     * HF-2 AC2 — find all OWNER users for a tenant schema.
     * Used by transfer notification fan-out where multiple owners can exist.
     */
    List<User> findOwnersByTenantSchemaName(String schemaName);

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
