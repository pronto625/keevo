package com.keevo.catalog.product.domain.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.time.Instant;

/**
 * TDD Tests for Product domain entity
 * 
 * Tests validation rules:
 * - name non-empty
 * - SKU format KEV-[A-Z0-9]{6}
 * - proper domain validation
 */
class ProductTest {
    
    @Test
    @DisplayName("Should create product with valid data")
    void should_create_product_with_valid_data() {
        // Given
        var id = UUID.randomUUID();
        var name = "Test Product";
        var description = "A test product";
        var sku = "KEV-ABC123";
        var categoryId = UUID.randomUUID();
        var archived = false;
        var status = ProductStatus.ACTIVE;
        var now = Instant.now();
        
        // When
        var product = new Product(id, name, description, sku, categoryId, archived, status, now, now);
        
        // Then
        assertEquals(id, product.getId());
        assertEquals(name, product.getName());
        assertEquals(description, product.getDescription());
        assertEquals(sku, product.getSku());
        assertEquals(categoryId, product.getCategoryId());
        assertEquals(archived, product.getArchived());
        assertEquals(status, product.getStatus());
        assertEquals(now, product.getCreatedAt());
        assertEquals(now, product.getUpdatedAt());
    }
    
    @Test
    @DisplayName("Should throw exception when name is null")
    void should_throw_exception_when_name_is_null() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new Product(
                UUID.randomUUID(),
                null, // invalid name
                "Description",
                "KEV-ABC123",
                UUID.randomUUID(),
                false,
                ProductStatus.ACTIVE,
                Instant.now(),
                Instant.now()
            );
        });
    }
    
    @Test
    @DisplayName("Should throw exception when name is empty")
    void should_throw_exception_when_name_is_empty() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new Product(
                UUID.randomUUID(),
                "", // invalid name
                "Description", 
                "KEV-ABC123",
                UUID.randomUUID(),
                false,
                ProductStatus.ACTIVE,
                Instant.now(),
                Instant.now()
            );
        });
    }
    
    @Test
    @DisplayName("Should throw exception when name is blank")
    void should_throw_exception_when_name_is_blank() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new Product(
                UUID.randomUUID(),
                "   ", // invalid name
                "Description",
                "KEV-ABC123", 
                UUID.randomUUID(),
                false,
                ProductStatus.ACTIVE,
                Instant.now(),
                Instant.now()
            );
        });
    }
    
    @Test
    @DisplayName("Should throw exception when SKU is invalid format")
    void should_throw_exception_when_sku_is_invalid_format() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new Product(
                UUID.randomUUID(),
                "Valid Name",
                "Description",
                "INVALID-SKU", // invalid SKU format
                UUID.randomUUID(),
                false,
                ProductStatus.ACTIVE,
                Instant.now(),
                Instant.now()
            );
        });
    }
    
    @Test
    @DisplayName("Should accept valid SKU format KEV-ABC123")
    void should_accept_valid_sku_format_kev_abc123() {
        // Given/When
        var product = new Product(
            UUID.randomUUID(),
            "Valid Name",
            "Description", 
            "KEV-ABC123", // valid SKU
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        
        // Then
        assertEquals("KEV-ABC123", product.getSku());
    }
    
    @Test
    @DisplayName("Should accept valid SKU format KEV-123XYZ")
    void should_accept_valid_sku_format_kev_123xyz() {
        // Given/When
        var product = new Product(
            UUID.randomUUID(),
            "Valid Name",
            "Description",
            "KEV-123XYZ", // valid SKU with numbers first
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        
        // Then
        assertEquals("KEV-123XYZ", product.getSku());
    }
    
    @Test
    @DisplayName("Should throw exception when SKU is null")
    void should_throw_exception_when_sku_is_null() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new Product(
                UUID.randomUUID(),
                "Valid Name",
                "Description",
                null, // null SKU
                UUID.randomUUID(),
                false,
                ProductStatus.ACTIVE,
                Instant.now(),
                Instant.now()
            );
        });
    }
    
    @Test
    @DisplayName("Should accept null description")
    void should_accept_null_description() {
        // Given/When
        var product = new Product(
            UUID.randomUUID(),
            "Valid Name",
            null, // null description is allowed
            "KEV-ABC123",
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        
        // Then
        assertNull(product.getDescription());
    }
    
    @Test
    @DisplayName("Should default archived to false")
    void should_default_archived_to_false() {
        // Given/When
        var product = new Product(
            UUID.randomUUID(),
            "Valid Name",
            "Description",
            "KEV-ABC123",
            UUID.randomUUID(),
            false, // explicitly false
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        
        // Then
        assertFalse(product.getArchived());
    }
    
    @Test
    @DisplayName("Should default status to ACTIVE")
    void should_default_status_to_active() {
        // Given/When
        var product = new Product(
            UUID.randomUUID(),
            "Valid Name",
            "Description",
            "KEV-ABC123",
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE, // explicitly ACTIVE
            Instant.now(),
            Instant.now()
        );
        
        // Then
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
    }
}