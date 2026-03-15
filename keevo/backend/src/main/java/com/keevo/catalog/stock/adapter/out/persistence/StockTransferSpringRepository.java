package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.StockTransferJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
