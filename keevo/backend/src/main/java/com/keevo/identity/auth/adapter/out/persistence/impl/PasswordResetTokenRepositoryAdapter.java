package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.PasswordResetTokenJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.PasswordResetTokenSpringRepository;
import com.keevo.identity.auth.domain.model.PasswordResetToken;
import com.keevo.identity.auth.domain.port.out.PasswordResetTokenRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * PasswordResetTokenRepositoryAdapter — JPA adapter for PasswordResetTokenRepository port.
 *
 * <p>Story 14.12 — Mirror of {@code RefreshTokenRepositoryAdapter} structure
 * (toEntity / toDomain pattern).
 */
@Component
public class PasswordResetTokenRepositoryAdapter implements PasswordResetTokenRepository {

    private final PasswordResetTokenSpringRepository springRepo;

    public PasswordResetTokenRepositoryAdapter(PasswordResetTokenSpringRepository springRepo) {
        this.springRepo = springRepo;
    }

    @Override
    public PasswordResetToken save(PasswordResetToken token) {
        PasswordResetTokenJpaEntity saved = springRepo.save(toEntity(token));
        return toDomain(saved);
    }

    @Override
    public Optional<PasswordResetToken> findActiveByPhoneNumber(String phoneNumber) {
        return springRepo
                .findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(phoneNumber)
                .map(this::toDomain);
    }

    @Override
    public void invalidateActiveTokensForUser(UUID userId) {
        springRepo.invalidateActiveTokensForUser(userId);
    }

    // ── Entity ↔ Domain mapping ───────────────────────────────────────

    private PasswordResetTokenJpaEntity toEntity(PasswordResetToken t) {
        return new PasswordResetTokenJpaEntity(
                t.id(), t.userId(), t.phoneNumber(),
                t.codeHash(), t.expiresAt(), t.consumedAt(), t.attempts()
        );
    }

    private PasswordResetToken toDomain(PasswordResetTokenJpaEntity e) {
        return new PasswordResetToken(
                e.getId(), e.getUserId(), e.getPhoneNumber(),
                e.getCodeHash(), e.getExpiresAt(), e.getConsumedAt(),
                e.getAttempts(), e.getCreatedAt()
        );
    }
}
