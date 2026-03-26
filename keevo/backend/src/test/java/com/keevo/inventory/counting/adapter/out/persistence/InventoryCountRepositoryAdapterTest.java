package com.keevo.inventory.counting.adapter.out.persistence;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventoryCountRepositoryAdapter — domain ↔ JPA mapping")
class InventoryCountRepositoryAdapterTest {

    private final InventoryCountRepositoryAdapter adapter =
            new InventoryCountRepositoryAdapter(null);

    @Test
    void toJpa_shouldMapAllFields() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID countedBy = UUID.randomUUID();
        Instant now = Instant.now();

        var count = new InventoryCount(id, sessionId, productId, variantId,
                "Produit A", "Taille M", 50, 47, now, countedBy, now);

        InventoryCountJpaEntity jpa = adapter.toJpa(count);

        assertThat(jpa.getId()).isEqualTo(id);
        assertThat(jpa.getSessionId()).isEqualTo(sessionId);
        assertThat(jpa.getProductId()).isEqualTo(productId);
        assertThat(jpa.getVariantId()).isEqualTo(variantId);
        assertThat(jpa.getProductName()).isEqualTo("Produit A");
        assertThat(jpa.getVariantLabel()).isEqualTo("Taille M");
        assertThat(jpa.getTheoretical()).isEqualTo(50);
        assertThat(jpa.getPhysical()).isEqualTo(47);
        assertThat(jpa.getCountedAt()).isEqualTo(now);
        assertThat(jpa.getCountedBy()).isEqualTo(countedBy);
        assertThat(jpa.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void toDomain_shouldMapAllFields() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID countedBy = UUID.randomUUID();
        Instant now = Instant.now();

        var entity = new InventoryCountJpaEntity();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setProductId(productId);
        entity.setVariantId(null);
        entity.setProductName("Savon bio");
        entity.setVariantLabel(null);
        entity.setTheoretical(100);
        entity.setPhysical(98);
        entity.setCountedAt(now);
        entity.setCountedBy(countedBy);
        entity.setUpdatedAt(now);

        InventoryCount domain = adapter.toDomain(entity);

        assertThat(domain.getId()).isEqualTo(id);
        assertThat(domain.getSessionId()).isEqualTo(sessionId);
        assertThat(domain.getProductId()).isEqualTo(productId);
        assertThat(domain.getVariantId()).isNull();
        assertThat(domain.getProductName()).isEqualTo("Savon bio");
        assertThat(domain.getVariantLabel()).isNull();
        assertThat(domain.getTheoretical()).isEqualTo(100);
        assertThat(domain.getPhysical()).isEqualTo(98);
        assertThat(domain.getCountedAt()).isEqualTo(now);
        assertThat(domain.getCountedBy()).isEqualTo(countedBy);
    }

    @Test
    void roundTrip_domain_toJpa_toDomain_shouldPreserveAllFields() {
        var original = InventoryCount.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "T-shirt", "Rouge XL", 25, 22, UUID.randomUUID());

        InventoryCountJpaEntity jpa = adapter.toJpa(original);
        InventoryCount roundTrip = adapter.toDomain(jpa);

        assertThat(roundTrip.getId()).isEqualTo(original.getId());
        assertThat(roundTrip.getSessionId()).isEqualTo(original.getSessionId());
        assertThat(roundTrip.getProductId()).isEqualTo(original.getProductId());
        assertThat(roundTrip.getVariantId()).isEqualTo(original.getVariantId());
        assertThat(roundTrip.getProductName()).isEqualTo(original.getProductName());
        assertThat(roundTrip.getVariantLabel()).isEqualTo(original.getVariantLabel());
        assertThat(roundTrip.getTheoretical()).isEqualTo(original.getTheoretical());
        assertThat(roundTrip.getPhysical()).isEqualTo(original.getPhysical());
        assertThat(roundTrip.getCountedBy()).isEqualTo(original.getCountedBy());
    }

    @Test
    void nullVariant_shouldRoundTripCorrectly() {
        var count = InventoryCount.create(
                UUID.randomUUID(), UUID.randomUUID(), null,
                "Simple product", null, 10, 8, UUID.randomUUID());

        InventoryCountJpaEntity jpa = adapter.toJpa(count);
        InventoryCount roundTrip = adapter.toDomain(jpa);

        assertThat(roundTrip.getVariantId()).isNull();
        assertThat(roundTrip.getVariantLabel()).isNull();
        assertThat(roundTrip.getTheoretical()).isEqualTo(10);
        assertThat(roundTrip.getPhysical()).isEqualTo(8);
    }
}
