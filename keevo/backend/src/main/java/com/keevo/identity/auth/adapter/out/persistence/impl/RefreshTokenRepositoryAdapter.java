package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.RefreshTokenJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.RefreshTokenSpringRepository;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * RefreshTokenRepositoryAdapter — Adapter bridging the domain {@link RefreshTokenRepository}
 * port to the Spring Data JPA {@link RefreshTokenSpringRepository}.
 *
 * <p>Lookup is always by SHA-256 hash — never by raw token value.
 * Architecture layer: {@code adapter/out/persistence/impl} — infrastructure only.
 */
@Component
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenSpringRepository springRepository;

    public RefreshTokenRepositoryAdapter(RefreshTokenSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        RefreshTokenJpaEntity saved = springRepository.save(toEntity(token));
        return toDomain(saved);
    }

    @Override
    public Optional<RefreshToken> findByHash(String tokenHash) {
        return springRepository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    @Override
    public void revokeAllByUserId(UUID userId) {
        springRepository.revokeAllByUserId(userId);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private RefreshTokenJpaEntity toEntity(RefreshToken t) {
        return new RefreshTokenJpaEntity(
            t.id(), t.userId(), t.tenantId(),
            t.tokenHash(), t.expiresAt(), t.revoked()
        );
    }

    private RefreshToken toDomain(RefreshTokenJpaEntity e) {
        return new RefreshToken(
            e.getId(), e.getUserId(), e.getTenantId(),
            e.getTokenHash(), e.getExpiresAt(), e.isRevoked()
        );
    }
}
