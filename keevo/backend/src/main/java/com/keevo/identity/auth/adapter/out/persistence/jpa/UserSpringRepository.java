package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * UserSpringRepository — Spring Data JPA interface for the public.users table.
 *
 * <p>Infrastructure layer only. Provides generated CRUD + custom queries.
 * Consumed exclusively by {@link com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter}.
 * Never injected directly into the domain or application layers.
 */
@Repository
public interface UserSpringRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    /** Story 19.2 — case-insensitive lookup on the {@code uq_users_email_lower} expression index. */
    @Query(value = "SELECT * FROM public.users WHERE lower(email) = lower(:email)", nativeQuery = true)
    Optional<UserJpaEntity> findByEmailIgnoreCase(@Param("email") String email);

    /** Story 19.4 — case-insensitive lookup on the {@code uq_users_username_lower} expression index. */
    @Query(value = "SELECT * FROM public.users WHERE lower(username) = lower(:username)", nativeQuery = true)
    Optional<UserJpaEntity> findByUsernameIgnoreCase(@Param("username") String username);
}
