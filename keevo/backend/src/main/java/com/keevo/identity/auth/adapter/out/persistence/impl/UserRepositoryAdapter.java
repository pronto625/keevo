package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserSpringRepository;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UserRepositoryAdapter — Adapter bridging the domain {@link UserRepository} port
 * to the Spring Data JPA {@link UserSpringRepository}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Translate domain {@link User} ↔ {@link UserJpaEntity} (anti-corruption layer)</li>
 *   <li>Shield the domain from any JPA / Spring Data concern</li>
 *   <li>Story 1.7: {@link #findMembershipsWithTenantInfo(UUID)} uses JdbcTemplate (NOT Hibernate)
 *       to join public.user_tenant_memberships + public.tenants without requiring TenantContext</li>
 * </ul>
 *
 * <p>Architecture layer: {@code adapter/out/persistence/impl} — infrastructure only.
 */
@Component
public class UserRepositoryAdapter implements UserRepository {

    private static final String MEMBERSHIPS_WITH_TENANT_SQL = """
            SELECT m.tenant_id, m.role, m.is_active,
                   t.code AS tenant_code, COALESCE(t.name, t.code) AS tenant_name, t.schema_name
            FROM public.user_tenant_memberships m
            JOIN public.tenants t ON t.id = m.tenant_id
            WHERE m.user_id = ? AND m.is_active = true
            """;

    /**
     * Story 19.1 — owners whose phone matches the recipient rule ({@code +<digits>}, as
     * {@link OwnerRecipientResolverAdapter}) come first (an email-only owner cannot receive WhatsApp), then the
     * oldest membership, tie-break on user id. Unchanged result for every tenant whose oldest owner has a phone.
     * {@link #OWNERS_BY_SCHEMA_SQL} uses the same order.
     */
    private static final String OWNER_BY_SCHEMA_SQL = """
            SELECT u.id, u.phone_number, u.password_hash, u.role, u.is_active,
                   u.created_at, u.failed_attempts, u.locked_until,
                   u.email, u.username, u.first_name, u.last_name, u.email_verified_at, u.phone_verified_at
            FROM public.users u
            JOIN public.user_tenant_memberships m ON u.id = m.user_id
            JOIN public.tenants t ON t.id = m.tenant_id
            WHERE t.schema_name = ? AND m.role = 'OWNER' AND m.is_active = true
              AND u.is_active = true
            ORDER BY (u.phone_number ~ '^\\+[0-9]+$') IS TRUE DESC, m.created_at ASC, u.id ASC
            LIMIT 1
            """;
    private static final String OWNERS_BY_SCHEMA_SQL = """
            SELECT u.id, u.phone_number, u.password_hash, u.role, u.is_active,
                   u.created_at, u.failed_attempts, u.locked_until,
                   u.email, u.username, u.first_name, u.last_name, u.email_verified_at, u.phone_verified_at
            FROM public.users u
            JOIN public.user_tenant_memberships m ON u.id = m.user_id
            JOIN public.tenants t ON t.id = m.tenant_id
            WHERE t.schema_name = ? AND m.role = 'OWNER' AND m.is_active = true
            ORDER BY (u.phone_number ~ '^\\+[0-9]+$') IS TRUE DESC, m.created_at ASC, u.id ASC
            """;

    /** Story 19.2 — any unique conflict (phone, lower(email), lower(username), id) leaves the row out. */
    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO public.users (id, phone_number, password_hash, role, is_active, failed_attempts, locked_until,
                                      email, username, first_name, last_name, email_verified_at, phone_verified_at,
                                      created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
    private static final String LOCK_BY_ID_SQL = "SELECT 1 FROM public.users WHERE id = ? FOR UPDATE";

    private final UserSpringRepository springRepository;
    private final JdbcTemplate jdbcTemplate;

    public UserRepositoryAdapter(UserSpringRepository springRepository,
                                  JdbcTemplate jdbcTemplate) {
        this.springRepository = springRepository;
        this.jdbcTemplate     = jdbcTemplate;
    }

    @Override
    public User save(User user) {
        UserJpaEntity saved = springRepository.save(toEntity(user));
        return toDomain(saved);
    }

    /**
     * Story 19.1 — a null or blank phone never reaches Spring Data: a derived query would translate
     * {@code null} into {@code phone_number IS NULL} and match phone-less (email/username-only) accounts.
     */
    @Override
    public Optional<User> findByPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        return springRepository.findByPhoneNumber(phoneNumber).map(UserRepositoryAdapter::toDomain);
    }

    /** Story 19.2 — null / blank never reaches the query (see {@link #findByPhoneNumber}). */
    @Override
    public Optional<User> findByEmail(String email) {
        String normalized = User.normalizeIdentifier(email);
        if (normalized == null) {
            return Optional.empty();
        }
        return springRepository.findByEmailIgnoreCase(normalized).map(UserRepositoryAdapter::toDomain);
    }

    @Override
    public boolean insertIfAbsent(User u) {
        Timestamp now = Timestamp.from(Instant.now());
        int inserted = jdbcTemplate.update(INSERT_IF_ABSENT_SQL,
                u.getId(), User.normalizeIdentifier(u.getPhoneNumber()), u.getPasswordHash(), u.getRole().name(),
                u.isActive(), u.failedAttempts(), ts(u.lockedUntil()),
                User.normalizeIdentifier(u.getEmail()), User.normalizeIdentifier(u.getUsername()),
                u.getFirstName(), u.getLastName(), ts(u.getEmailVerifiedAt()), ts(u.getPhoneVerifiedAt()),
                now, now);
        return inserted == 1;
    }

    @Override
    public boolean lockById(UUID id) {
        return !jdbcTemplate.queryForList(LOCK_BY_ID_SQL, Integer.class, id).isEmpty();
    }

    private static Timestamp ts(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return springRepository.findById(id).map(UserRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsByPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return false; // Story 19.1 — see findByPhoneNumber
        }
        return springRepository.existsByPhoneNumber(phoneNumber);
    }

    /**
     * Story 1.7 — Load all active memberships for a user with tenant metadata.
     *
     * <p>Uses JdbcTemplate (NOT Hibernate) because TenantContext is NOT set at login time.
     * A plain JDBC query on public.* tables bypasses the multi-tenant connection provider
     * and always targets the correct public schema.
     */
    @Override
    public List<UserMembershipInfo> findMembershipsWithTenantInfo(UUID userId) {
        return jdbcTemplate.query(
                MEMBERSHIPS_WITH_TENANT_SQL,
                (rs, rowNum) -> new UserMembershipInfo(
                        rs.getString("tenant_code"),
                        rs.getString("tenant_name"),
                        rs.getString("role"),
                        rs.getString("schema_name")),
                userId);
    }

    /**
     * Story 7.2 — Find the OWNER user for WhatsApp delivery phone resolution.
     */
    @Override
    public Optional<User> findOwnerByTenantSchemaName(String schemaName) {
        List<User> results = jdbcTemplate.query(
                OWNER_BY_SCHEMA_SQL,
                (rs, rowNum) -> mapUserRow(rs),
                schemaName);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<User> findOwnersByTenantSchemaName(String schemaName) {
        return jdbcTemplate.query(
                OWNERS_BY_SCHEMA_SQL,
                (rs, rowNum) -> mapUserRow(rs),
                schemaName);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private static User mapUserRow(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new User(
            (UUID) rs.getObject("id"),
            rs.getString("phone_number"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            rs.getBoolean("is_active"),
            createdAt != null ? createdAt.toInstant() : Instant.now(),
            rs.getInt("failed_attempts"),
            instant(rs.getTimestamp("locked_until")),
            rs.getString("email"),
            rs.getString("username"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            instant(rs.getTimestamp("email_verified_at")),
            instant(rs.getTimestamp("phone_verified_at"))
        );
    }

    private static Instant instant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    static UserJpaEntity toEntity(User u) {
        return new UserJpaEntity(
            u.getId(), User.normalizeIdentifier(u.getPhoneNumber()), u.getPasswordHash(),
            u.getRole().name(), u.isActive(),
            u.failedAttempts(), u.lockedUntil(),
            User.normalizeIdentifier(u.getEmail()), User.normalizeIdentifier(u.getUsername()),
            u.getFirstName(), u.getLastName(),
            u.getEmailVerifiedAt(), u.getPhoneVerifiedAt()
        );
    }

    static User toDomain(UserJpaEntity e) {
        return new User(
            e.getId(), e.getPhoneNumber(), e.getPasswordHash(),
            Role.valueOf(e.getRole()), e.isActive(),
            e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now(),
            e.getFailedAttempts(), e.getLockedUntil(),
            e.getEmail(), e.getUsername(), e.getFirstName(), e.getLastName(),
            e.getEmailVerifiedAt(), e.getPhoneVerifiedAt()
        );
    }
}

