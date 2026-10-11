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
    default int transitionStatus(UUID id, TransferStatus expectedStatus, TransferStatus newStatus) {
        return transitionStatusNative(id, expectedStatus.name(), newStatus.name());
    }

    /**
     * Native because updated_at (the delta pull cursor for transfers) is not mapped on the
     * entity: the public baseline table lacks it and ddl-auto=validate would reject the mapping.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE stock_transfers SET status = :newStatus, version = version + 1, " +
           "updated_at = now() WHERE id = :id AND status = :expectedStatus",
           nativeQuery = true)
    int transitionStatusNative(@Param("id") UUID id,
                               @Param("expectedStatus") String expectedStatus,
                               @Param("newStatus") String newStatus);
}
