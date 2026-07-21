package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import com.keevo.shared.infrastructure.persistence.entity.StockTransferJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * StockTransferSpringRepository — Spring Data JPA repository for stock transfers.
 *
 * <p>Uses JpaSpecificationExecutor to support dynamic filtering without HQL
 * nullable-param issues (Hibernate 6 compatibility).
 *
 * Story 3.3.
 */
public interface StockTransferSpringRepository
        extends JpaRepository<StockTransferJpaEntity, UUID>,
                JpaSpecificationExecutor<StockTransferJpaEntity> {

    /**
     * Atomic conditional status transition — UPDATE WHERE status = expectedStatus.
     * Returns the number of rows updated (0 or 1). Two concurrent calls →
     * exactly one returns 1, the other returns 0.
     * Story v1s-13-1 (Option A).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockTransferJpaEntity t SET t.status = :newStatus, t.version = t.version + 1 " +
           "WHERE t.id = :id AND t.status = :expectedStatus")
    int transitionStatus(@Param("id") UUID id,
                         @Param("expectedStatus") TransferStatus expectedStatus,
                         @Param("newStatus") TransferStatus newStatus);
}
