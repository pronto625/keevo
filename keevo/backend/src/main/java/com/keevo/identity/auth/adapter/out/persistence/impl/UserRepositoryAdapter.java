package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserSpringRepository;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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

    @Override
    public Optional<User> findByPhoneNumber(String phoneNumber) {
        return springRepository.findByPhoneNumber(phoneNumber).map(this::toDomain);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return springRepository.findById(id).map(this::toDomain);
    }

    @Override
    public boolean existsByPhoneNumber(String phoneNumber) {
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

    // ── Mapping ──────────────────────────────────────────────────────────────

    private UserJpaEntity toEntity(User u) {
        return new UserJpaEntity(
            u.getId(), u.getPhoneNumber(), u.getPasswordHash(),
            u.getRole().name(), u.isActive(),
            u.failedAttempts(), u.lockedUntil()
        );
    }

    private User toDomain(UserJpaEntity e) {
        return new User(
            e.getId(), e.getPhoneNumber(), e.getPasswordHash(),
            Role.valueOf(e.getRole()), e.isActive(),
            e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now(),
            e.getFailedAttempts(), e.getLockedUntil()
        );
    }
}

