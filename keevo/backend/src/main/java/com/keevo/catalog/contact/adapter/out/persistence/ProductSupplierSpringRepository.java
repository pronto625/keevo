package com.keevo.catalog.contact.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.ProductSupplierJpaEntity;
import com.keevo.shared.infrastructure.persistence.entity.ProductSupplierJpaEntity.ProductSupplierId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * ProductSupplierSpringRepository — Spring Data JPA interface for the
 * {@code product_suppliers} join table (Story 2.5).
 */
public interface ProductSupplierSpringRepository
        extends JpaRepository<ProductSupplierJpaEntity, ProductSupplierId> {

    @Query("SELECT ps.id.productId FROM ProductSupplierJpaEntity ps WHERE ps.id.supplierId = :supplierId")
    List<UUID> findProductIdsBySupplierId(@Param("supplierId") UUID supplierId);

    @Modifying
    @Query("DELETE FROM ProductSupplierJpaEntity ps WHERE ps.id.supplierId = :supplierId")
    void deleteAllBySupplierId(@Param("supplierId") UUID supplierId);

    @Query("SELECT ps.id.supplierId FROM ProductSupplierJpaEntity ps WHERE ps.id.productId = :productId")
    java.util.Optional<UUID> findSupplierIdByProductId(@Param("productId") UUID productId);
}
