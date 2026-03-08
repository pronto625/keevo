package com.keevo.catalog.product.domain.port.out;

import com.keevo.catalog.product.domain.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.UUID;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Contract test for ProductRepository port
 * 
 * Tests ALL interface methods are properly defined:
 * - save(Product)
 * - findById(UUID) 
 * - findAll()
 * - findAllActive() (archived=false)
 * - findBySku(String)
 * - archive(UUID) (soft delete)
 */
class ProductRepositoryContractTest {
    
    // This is a contract test - we test the interface exists with proper signatures
    // Actual implementation behavior is tested in the adapter tests
    
    @Test
    @DisplayName("Should define save method with proper signature")
    void should_define_save_method_with_proper_signature() throws NoSuchMethodException {
        // When
        var method = ProductRepository.class.getMethod("save", Product.class);
        
        // Then
        assertEquals(Product.class, method.getReturnType());
        assertEquals("save", method.getName());
        assertEquals(1, method.getParameterCount());
        assertEquals(Product.class, method.getParameterTypes()[0]);
    }
    
    @Test
    @DisplayName("Should define findById method with proper signature") 
    void should_define_find_by_id_method_with_proper_signature() throws NoSuchMethodException {
        // When
        var method = ProductRepository.class.getMethod("findById", UUID.class);
        
        // Then
        assertEquals(Optional.class, method.getReturnType());
        assertEquals("findById", method.getName());
        assertEquals(1, method.getParameterCount());
        assertEquals(UUID.class, method.getParameterTypes()[0]);
    }
    
    @Test
    @DisplayName("Should define findAll method with proper signature")
    void should_define_find_all_method_with_proper_signature() throws NoSuchMethodException {
        // When
        var method = ProductRepository.class.getMethod("findAll");
        
        // Then
        assertEquals(List.class, method.getReturnType());
        assertEquals("findAll", method.getName()); 
        assertEquals(0, method.getParameterCount());
    }
    
    @Test
    @DisplayName("Should define findAllActive method with proper signature")
    void should_define_find_all_active_method_with_proper_signature() throws NoSuchMethodException {
        // When
        var method = ProductRepository.class.getMethod("findAllActive");
        
        // Then
        assertEquals(List.class, method.getReturnType());
        assertEquals("findAllActive", method.getName());
        assertEquals(0, method.getParameterCount());
    }
    
    @Test
    @DisplayName("Should define findBySku method with proper signature")
    void should_define_find_by_sku_method_with_proper_signature() throws NoSuchMethodException {
        // When
        var method = ProductRepository.class.getMethod("findBySku", String.class);
        
        // Then
        assertEquals(Optional.class, method.getReturnType());
        assertEquals("findBySku", method.getName());
        assertEquals(1, method.getParameterCount());
        assertEquals(String.class, method.getParameterTypes()[0]);
    }
    
    @Test
    @DisplayName("Should define archive method with proper signature")
    void should_define_archive_method_with_proper_signature() throws NoSuchMethodException {
        // When
        var method = ProductRepository.class.getMethod("archive", UUID.class);
        
        // Then
        assertEquals(void.class, method.getReturnType());
        assertEquals("archive", method.getName());
        assertEquals(1, method.getParameterCount());
        assertEquals(UUID.class, method.getParameterTypes()[0]);
    }
    
    @Test
    @DisplayName("ProductRepository should be an interface")
    void should_be_an_interface() {
        // Then
        assertTrue(ProductRepository.class.isInterface());
    }
}