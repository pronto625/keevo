package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * JpaUserRepository — Persistence adapter implementing the UserRepository port.
 *
 * <p>Adapts between the domain User model and UserJpaEntity.
 * Maps JPA entity ↔ domain model (Anti-corruption layer).
 */
@Component
public class JpaUserRepository implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public JpaUserRepository(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = toJpaEntity(user);
        UserJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<User> findByPhoneNumber(String phoneNumber) {
        return jpaRepository.findByPhoneNumber(phoneNumber)
            .map(this::toDomain);
    }

    @Override
    public boolean existsByPhoneNumber(String phoneNumber) {
        return jpaRepository.existsByPhoneNumber(phoneNumber);
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private UserJpaEntity toJpaEntity(User user) {
        return new UserJpaEntity(
            user.getId(),
            user.getPhoneNumber(),
            user.getPasswordHash(),
            user.getRole().name(),
            user.getTenantId(),
            user.isActive()
        );
    }

    private User toDomain(UserJpaEntity entity) {
        return new User(
            entity.getId(),
            entity.getPhoneNumber(),
            entity.getPasswordHash(),
            Role.valueOf(entity.getRole()),
            entity.getTenantId(),
            entity.isActive(),
            entity.getCreatedAt() != null ? entity.getCreatedAt() : Instant.now()
        );
    }
}
