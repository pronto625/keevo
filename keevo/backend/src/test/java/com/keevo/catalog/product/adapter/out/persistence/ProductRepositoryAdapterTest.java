package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.time.Instant;

/**
 * TDD Test for ProductRepositoryAdapter
 *
 * Tests that adapter correctly implements CRUD operations
 */
@ExtendWith(SpringExtension.class)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql("/product-schema.sql")
@ContextConfiguration(classes = {ProductRepositoryAdapterTest.TestConfig.class})
class ProductRepositoryAdapterTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ProductRepositoryAdapter productRepositoryAdapter(ProductSpringRepository springRepository) {
            return new ProductRepositoryAdapter(springRepository);
        }
    }

    @Autowired
    private ProductRepositoryAdapter productRepository;

    @Test
    @DisplayName("Should save and retrieve product correctly")
    void should_save_and_retrieve_product_correctly() {
        // Given
        var product = new Product(
            UUID.randomUUID(),
            "Test Product",
            "Test Description",
            "KEV-TST123",
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );

        // When
        var saved = productRepository.save(product);

        // Then
        assertNotNull(saved);
        assertEquals(product.getName(), saved.getName());
        assertEquals(product.getDescription(), saved.getDescription());
        assertEquals(product.getSku(), saved.getSku());
        assertEquals(product.getArchived(), saved.getArchived());
        assertEquals(product.getStatus(), saved.getStatus());
    }

    @Test
    @DisplayName("Should find product by ID")
    void should_find_product_by_id() {
        // Given
        var product = new Product(
            UUID.randomUUID(),
            "Test Product",
            "Test Description", 
            "KEV-TST124",
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        var saved = productRepository.save(product);

        // When
        var found = productRepository.findById(saved.getId());

        // Then
        assertTrue(found.isPresent());
        assertEquals(saved.getName(), found.get().getName());
        assertEquals(saved.getSku(), found.get().getSku());
    }

    @Test
    @DisplayName("Should find product by SKU")
    void should_find_product_by_sku() {
        // Given
        var product = new Product(
            UUID.randomUUID(),
            "Test Product",
            "Test Description",
            "KEV-TST125",
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        productRepository.save(product);

        // When
        var found = productRepository.findBySku("KEV-TST125");

        // Then
        assertTrue(found.isPresent());
        assertEquals("Test Product", found.get().getName());
    }

    @Test
    @DisplayName("Should find all active products excluding archived")
    void should_find_all_active_products_excluding_archived() {
        // Given
        var activeProduct = new Product(
            UUID.randomUUID(),
            "Active Product",
            "Description",
            "KEV-ACT126",
            UUID.randomUUID(),
            false, // not archived
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        
        var archivedProduct = new Product(
            UUID.randomUUID(),
            "Archived Product", 
            "Description",
            "KEV-ARC127",
            UUID.randomUUID(),
            true, // archived
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );

        productRepository.save(activeProduct);
        productRepository.save(archivedProduct);

        // When
        var activeProducts = productRepository.findAllActive();

        // Then
        assertEquals(1, activeProducts.size());
        assertEquals("Active Product", activeProducts.get(0).getName());
        assertEquals(false, activeProducts.get(0).getArchived());
    }

    @Test
    @DisplayName("Should archive product by ID")
    void should_archive_product_by_id() {
        // Given
        var product = new Product(
            UUID.randomUUID(),
            "Test Product",
            "Description",
            "KEV-TST128", 
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        var saved = productRepository.save(product);

        // When
        productRepository.archive(saved.getId());

        // Then
        var archived = productRepository.findById(saved.getId());
        assertTrue(archived.isPresent());
        assertTrue(archived.get().getArchived());
    }

    @Test
    @DisplayName("Should return empty when product not found by ID")
    void should_return_empty_when_product_not_found_by_id() {
        // When
        var found = productRepository.findById(UUID.randomUUID());

        // Then
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("Should return empty when product not found by SKU")
    void should_return_empty_when_product_not_found_by_sku() {
        // When
        var found = productRepository.findBySku("NONEXISTENT");

        // Then
        assertTrue(found.isEmpty());
    }
}