package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.RefreshTokenJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RefreshTokenSpringRepository — Spring Data JPA interface for the public.refresh_tokens table.
 *
 * <p>Infrastructure layer only. Provides lookup by SHA-256 hash and bulk revocation.
 * Consumed exclusively by
 * {@link com.keevo.identity.auth.adapter.out.persistence.impl.RefreshTokenRepositoryAdapter}.
 * Never injected directly into the domain or application layers.
 */
@Repository
public interface RefreshTokenSpringRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {

    /** Row-locked: two parallel refreshes of one token are serialised, not both rotated. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revoked = true WHERE r.userId = :userId")
    void revokeAllByUserId(@Param("userId") UUID userId);

    /** Story 16.11 — live tokens (one per open device session) of a user in one account. */
    @Query("SELECT r FROM RefreshTokenJpaEntity r WHERE r.userId = :userId AND r.tenantId = :tenantId "
            + "AND r.revoked = false AND r.expiresAt > :now ORDER BY r.lastUsedAt DESC NULLS LAST")
    List<RefreshTokenJpaEntity> findLive(@Param("userId") UUID userId, @Param("tenantId") String tenantId,
                                         @Param("now") Instant now);

    /** Story 16.11 — closes one device session of a user in one account (revokes its live tokens). */
    @Modifying
    @Query(value = "UPDATE public.refresh_tokens SET revoked = true, updated_at = now() "
            + "WHERE user_id = :userId AND tenant_id = :tenantId AND session_id = :sessionId "
            + "AND revoked = false", nativeQuery = true)
    int revokeSession(@Param("userId") UUID userId, @Param("tenantId") String tenantId,
                      @Param("sessionId") UUID sessionId);

    /** Story 16.11 — revokes every live token of a device chain (replay detected). */
    @Modifying
    @Query(value = "UPDATE public.refresh_tokens SET revoked = true, updated_at = now() "
            + "WHERE session_id = :sessionId AND revoked = false", nativeQuery = true)
    int revokeSessionChain(@Param("sessionId") UUID sessionId);

    /** Story 12.12 — purge expired refresh tokens (see port). */
    @Transactional // own tx when called by the cleanup job
    @Modifying
    @Query(value = "DELETE FROM public.refresh_tokens WHERE expires_at < :cutoff", nativeQuery = true)
    int purgeExpiredBefore(@Param("cutoff") Instant cutoff);
}
