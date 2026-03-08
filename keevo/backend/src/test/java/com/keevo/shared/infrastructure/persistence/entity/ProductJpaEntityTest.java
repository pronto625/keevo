package com.keevo.shared.infrastructure.persistence.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.time.Instant;
import com.keevo.catalog.product.domain.entity.ProductStatus;

/**
 * TDD Test for ProductJpaEntity JPA mapping
 *
 * Tests JPA entity mapping to products table
 */
@ExtendWith(SpringExtension.class)
@DataJpaTest
@Sql("/product-schema.sql")
class ProductJpaEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Should persist and retrieve ProductJpaEntity correctly")
    void should_persist_and_retrieve_product_jpa_entity_correctly() {
        // Given
        var productEntity = new ProductJpaEntity();
        productEntity.setId(UUID.randomUUID());
        productEntity.setName("Test Product");
        productEntity.setDescription("Test Description");
        productEntity.setSku("KEV-TST123");
        productEntity.setCategoryId(UUID.randomUUID());
        productEntity.setPrice(5000);
        productEntity.setBuyPrice(3000);
        productEntity.setStockQuantity(10);
        productEntity.setPhotoUrl("https://example.com/photo.jpg");
        productEntity.setArchived(false);
        productEntity.setStatus(ProductStatus.ACTIVE);
        productEntity.setCreatedAt(Instant.now());
        productEntity.setUpdatedAt(Instant.now());

        // When
        var saved = entityManager.persistAndFlush(productEntity);

        // Then
        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("Test Product", saved.getName());
        assertEquals("Test Description", saved.getDescription());
        assertEquals("KEV-TST123", saved.getSku());
        assertEquals(5000, saved.getPrice());
        assertEquals(3000, saved.getBuyPrice());
        assertEquals(10, saved.getStockQuantity());
        assertEquals("https://example.com/photo.jpg", saved.getPhotoUrl());
        assertEquals(false, saved.getArchived());
        assertEquals(ProductStatus.ACTIVE, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("Should handle null description correctly")
    void should_handle_null_description_correctly() {
        // Given
        var productEntity = new ProductJpaEntity();
        productEntity.setId(UUID.randomUUID());
        productEntity.setName("Test Product");
        productEntity.setDescription(null); // null description
        productEntity.setSku("KEV-TST124");
        productEntity.setCategoryId(UUID.randomUUID());
        productEntity.setArchived(false);
        productEntity.setStatus(ProductStatus.ACTIVE);
        productEntity.setCreatedAt(Instant.now());
        productEntity.setUpdatedAt(Instant.now());

        // When
        var saved = entityManager.persistAndFlush(productEntity);

        // Then
        assertNotNull(saved);
        assertNull(saved.getDescription());
    }

    @Test
    @DisplayName("Should enforce name NOT NULL constraint")
    void should_enforce_name_not_null_constraint() {
        // Given
        var productEntity = new ProductJpaEntity();
        productEntity.setId(UUID.randomUUID());
        productEntity.setName(null); // null name should violate constraint
        productEntity.setSku("KEV-TST125");
        productEntity.setCategoryId(UUID.randomUUID());
        productEntity.setArchived(false);
        productEntity.setStatus(ProductStatus.ACTIVE);
        productEntity.setCreatedAt(Instant.now());
        productEntity.setUpdatedAt(Instant.now());

        // When/Then
        assertThrows(Exception.class, () -> {
            entityManager.persistAndFlush(productEntity);
        });
    }
}