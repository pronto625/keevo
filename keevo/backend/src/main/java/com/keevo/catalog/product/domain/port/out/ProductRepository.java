package com.keevo.catalog.product.domain.port.out;

import com.keevo.catalog.product.domain.entity.Product;

import java.util.UUID;
import java.util.List;
import java.util.Optional;

/**
 * Product Repository Port (Hexagonal Architecture)
 * 
 * Defines contract for product persistence operations.
 * Implementation will handle tenant isolation via TenantContext.
 * 
 * Methods:
 * - save(Product) - persists new or updated product
 * - findById(UUID) - retrieves product by ID
 * - findAll() - retrieves all products (including archived) 
 * - findAllActive() - retrieves only non-archived products
 * - findBySku(String) - retrieves product by unique SKU
 * - archive(UUID) - soft delete (sets archived=true)
 */
public interface ProductRepository {
    
    /**
     * Saves a product (create or update)
     * 
     * @param product the product to save
     * @return the saved product with potentially updated fields
     */
    Product save(Product product);
    
    /**
     * Finds a product by its ID
     * 
     * @param id the product ID
     * @return Optional containing the product if found, empty otherwise
     */
    Optional<Product> findById(UUID id);
    
    /**
     * Finds all products (including archived ones)
     * 
     * @return list of all products in current tenant
     */
    List<Product> findAll();
    
    /**
     * Finds all active (non-archived) products
     * 
     * @return list of active products in current tenant
     */
    List<Product> findAllActive();
    
    /**
     * Finds a product by its unique SKU
     * 
     * @param sku the product SKU
     * @return Optional containing the product if found, empty otherwise
     */
    Optional<Product> findBySku(String sku);

    /**
     * Returns true if a product with the given name exists for this tenant,
     * regardless of status (DRAFT or ACTIVE). Case-insensitive, trimmed.
     * Used to enforce the no-duplicate name invariant (AC8 — Story 2.4).
     */
    boolean existsByName(String name);

    /**
     * Archives a product (soft delete)
     * Sets archived=true without physically removing the data.
     *
     * @param id the product ID to archive
     */
    void archive(UUID id);
}