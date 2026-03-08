package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

/**
 * TDD Test for TenantSchemaProvisioner DDL constants
 * 
 * Tests that Product DDL constants are properly defined (simplified version)
 */
class TenantSchemaProvisionerProductDDLTest {
    
    @Test
    @DisplayName("Should define DDL_PRODUCTS constant with essential content")
    void should_define_ddl_products_constant() throws NoSuchFieldException, IllegalAccessException {
        // When
        Field ddlProductsField = TenantSchemaProvisioner.class.getDeclaredField("DDL_PRODUCTS");
        ddlProductsField.setAccessible(true);
        String ddlProducts = (String) ddlProductsField.get(null);
        
        // Then - verify essential content is present (flexible to formatting)
        assertNotNull(ddlProducts);
        assertTrue(ddlProducts.contains("CREATE TABLE IF NOT EXISTS products"));
        assertTrue(ddlProducts.contains("id") && ddlProducts.contains("UUID")); 
        assertTrue(ddlProducts.contains("name")); 
        assertTrue(ddlProducts.contains("sku"));
        assertTrue(ddlProducts.contains("category_id"));
        assertTrue(ddlProducts.contains("archived"));
        assertTrue(ddlProducts.contains("status"));
        assertTrue(ddlProducts.contains("CHECK"));
        assertTrue(ddlProducts.contains("ACTIVE"));
        assertTrue(ddlProducts.contains("DRAFT"));
    }
    
    @Test
    @DisplayName("Should define DDL_PRODUCTS_IDX_SKU constant")
    void should_define_ddl_products_idx_sku_constant() throws NoSuchFieldException, IllegalAccessException {
        // When
        Field field = TenantSchemaProvisioner.class.getDeclaredField("DDL_PRODUCTS_IDX_SKU");
        field.setAccessible(true);
        String indexSql = (String) field.get(null);
        
        // Then
        assertNotNull(indexSql);
        assertTrue(indexSql.contains("CREATE INDEX"));
        assertTrue(indexSql.contains("idx_products_sku"));
        assertTrue(indexSql.contains("products"));
        assertTrue(indexSql.contains("sku"));
    }
    
    @Test
    @DisplayName("Should define DDL_PRODUCTS_IDX_ARCHIVED constant")
    void should_define_ddl_products_idx_archived_constant() throws NoSuchFieldException, IllegalAccessException {
        // When
        Field field = TenantSchemaProvisioner.class.getDeclaredField("DDL_PRODUCTS_IDX_ARCHIVED");
        field.setAccessible(true);
        String indexSql = (String) field.get(null);
        
        // Then
        assertNotNull(indexSql);
        assertTrue(indexSql.contains("CREATE INDEX"));
        assertTrue(indexSql.contains("idx_products_archived"));
        assertTrue(indexSql.contains("products"));
        assertTrue(indexSql.contains("archived"));
    }
}