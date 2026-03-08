package com.keevo.catalog.product.domain.entity;

/**
 * Product status enumeration
 * 
 * ACTIVE - Product is active and visible in POS
 * DRAFT - Product created on-the-fly during POS sale (Story 2.4)
 */
public enum ProductStatus {
    ACTIVE,
    DRAFT
}