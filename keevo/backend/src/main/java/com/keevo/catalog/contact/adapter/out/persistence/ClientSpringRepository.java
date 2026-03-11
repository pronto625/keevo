package com.keevo.catalog.contact.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.ClientJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * ClientSpringRepository — Spring Data JPA interface for {@code ClientJpaEntity}.
 */
public interface ClientSpringRepository extends JpaRepository<ClientJpaEntity, UUID> {

    @Query("SELECT c FROM ClientJpaEntity c WHERE (:includeArchived = TRUE OR c.archived = FALSE) ORDER BY c.name")
    List<ClientJpaEntity> findAllFiltered(@Param("includeArchived") boolean includeArchived);

    @Query("SELECT c FROM ClientJpaEntity c WHERE c.archived = FALSE AND " +
           "(LOWER(c.name) LIKE LOWER(CONCAT('%',:q,'%')) OR c.phone LIKE CONCAT('%',:q,'%'))")
    List<ClientJpaEntity> searchByNameOrPhone(@Param("q") String query);
}
