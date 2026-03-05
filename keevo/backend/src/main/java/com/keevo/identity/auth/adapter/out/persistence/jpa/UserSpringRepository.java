package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
