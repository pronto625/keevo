package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.RefreshTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revoked = true WHERE r.userId = :userId")
    void revokeAllByUserId(@Param("userId") UUID userId);
}
