package com.keevo.catalog.category.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * CategoryJpaRepository — Spring Data JPA repository for category entities.
 *
 * <p>findAllActiveByQuery uses a JPQL WHERE clause instead of Java-side filtering,
 * ensuring the {@code idx_categories_is_active} index is leveraged at the DB level.
 */
public interface CategoryJpaRepository extends JpaRepository<CategoryJpaEntity, UUID> {

    @Query("SELECT c FROM CategoryJpaEntity c WHERE c.isActive = true")
    List<CategoryJpaEntity> findAllActive();
    
    @Query("SELECT c FROM CategoryJpaEntity c WHERE c.parentId = :parentId")
    List<CategoryJpaEntity> findByParentId(UUID parentId);

    @Query("SELECT c FROM CategoryJpaEntity c WHERE c.name = :name AND (:parentId IS NULL AND c.parentId IS NULL OR c.parentId = :parentId)")
    java.util.Optional<CategoryJpaEntity> findByNameAndParentId(String name, UUID parentId);
}