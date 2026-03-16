package com.keevo.identity.employee.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EmployeeSpringRepository — Spring Data JPA repository for EmployeeJpaEntity.
 * Story 3.5.
 */
public interface EmployeeSpringRepository extends JpaRepository<EmployeeJpaEntity, UUID> {

    Optional<EmployeeJpaEntity> findByUserId(UUID userId);

    List<EmployeeJpaEntity> findAllByOrderByStatusAscCreatedAtAsc();
}
