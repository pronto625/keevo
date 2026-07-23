package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.PasswordResetTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * PasswordResetTokenSpringRepository — Spring Data JPA repository for password_reset_tokens.
 *
 * <p>Story 14.12 — Mirror of RefreshTokenSpringRepository pattern.
 */
@Repository
public interface PasswordResetTokenSpringRepository
        extends JpaRepository<PasswordResetTokenJpaEntity, UUID> {

    /**
     * Find the most recent active (non-consumed) token for a phone number.
     */
    Optional<PasswordResetTokenJpaEntity>
            findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(String phoneNumber);

    /**
     * Invalidate all active tokens for a given user.
     * Sets consumed_at = NOW() on all non-consumed rows for that user.
     */
    @Modifying
    @Query("""
        UPDATE PasswordResetTokenJpaEntity t
           SET t.consumedAt = CURRENT_TIMESTAMP
         WHERE t.userId = :userId
           AND t.consumedAt IS NULL
        """)
    void invalidateActiveTokensForUser(@Param("userId") UUID userId);
}
