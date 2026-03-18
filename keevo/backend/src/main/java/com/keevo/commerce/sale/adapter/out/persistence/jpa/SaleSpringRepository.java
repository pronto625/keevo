package com.keevo.commerce.sale.adapter.out.persistence.jpa;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SaleSpringRepository extends JpaRepository<SaleJpaEntity, UUID> {

    @Query("SELECT s FROM SaleJpaEntity s JOIN s.items i WHERE s.status = 'PENDING_VALIDATION' AND i.productId = :productId")
    List<SaleJpaEntity> findPendingByProductId(@Param("productId") UUID productId);

    List<SaleJpaEntity> findByStatus(String status);
}
