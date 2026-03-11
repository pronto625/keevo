package com.keevo.catalog.contact.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.SupplierJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * SupplierSpringRepository — Spring Data JPA interface for {@code SupplierJpaEntity}.
 */
public interface SupplierSpringRepository extends JpaRepository<SupplierJpaEntity, UUID> {

    @Query("SELECT s FROM SupplierJpaEntity s WHERE (:includeArchived = TRUE OR s.archived = FALSE) ORDER BY s.name")
    List<SupplierJpaEntity> findAllFiltered(@Param("includeArchived") boolean includeArchived);

    @Query("SELECT s FROM SupplierJpaEntity s WHERE s.archived = FALSE AND " +
           "LOWER(s.name) LIKE LOWER(CONCAT('%',:q,'%'))")
    List<SupplierJpaEntity> searchByName(@Param("q") String query);
}
