package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserSpringRepository;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
 * </ul>
 *
 * <p>Architecture layer: {@code adapter/out/persistence/impl} — infrastructure only.
 */
@Component
public class UserRepositoryAdapter implements UserRepository {

    private final UserSpringRepository springRepository;

    public UserRepositoryAdapter(UserSpringRepository springRepository) {
        this.springRepository = springRepository;
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

    // ── Mapping ──────────────────────────────────────────────────────────────

    private UserJpaEntity toEntity(User u) {
        return new UserJpaEntity(
            u.getId(), u.getPhoneNumber(), u.getPasswordHash(),
            u.getRole().name(), u.getTenantId(), u.isActive(),
            u.failedAttempts(), u.lockedUntil()
        );
    }

    private User toDomain(UserJpaEntity e) {
        return new User(
            e.getId(), e.getPhoneNumber(), e.getPasswordHash(),
            Role.valueOf(e.getRole()), e.getTenantId(), e.isActive(),
            e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now(),
            e.getFailedAttempts(), e.getLockedUntil()
        );
    }
}
