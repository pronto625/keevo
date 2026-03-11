package com.keevo.shared.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * ProductSupplierJpaEntity — JPA mapping for {@code product_suppliers} join table (Story 2.5).
 *
 * <p>Uses a composite primary key (product_id, supplier_id).
 */
@Entity
@Table(name = "product_suppliers")
public class ProductSupplierJpaEntity {

    @EmbeddedId
    private ProductSupplierId id;

    public ProductSupplierJpaEntity() {}

    public ProductSupplierJpaEntity(UUID productId, UUID supplierId) {
        this.id = new ProductSupplierId(productId, supplierId);
    }

    public ProductSupplierId getId()               { return id; }
    public void setId(ProductSupplierId id)        { this.id = id; }

    public UUID getProductId()  { return id != null ? id.productId  : null; }
    public UUID getSupplierId() { return id != null ? id.supplierId : null; }

    // ── Composite key ─────────────────────────────────────────────────────────

    @Embeddable
    public static class ProductSupplierId implements Serializable {

        @Column(name = "product_id", columnDefinition = "UUID")
        private UUID productId;

        @Column(name = "supplier_id", columnDefinition = "UUID")
        private UUID supplierId;

        public ProductSupplierId() {}

        public ProductSupplierId(UUID productId, UUID supplierId) {
            this.productId  = productId;
            this.supplierId = supplierId;
        }

        public UUID getProductId()  { return productId; }
        public UUID getSupplierId() { return supplierId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProductSupplierId that = (ProductSupplierId) o;
            return Objects.equals(productId, that.productId) &&
                   Objects.equals(supplierId, that.supplierId);
        }

        @Override
        public int hashCode() { return Objects.hash(productId, supplierId); }
    }
}
