package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Write access to the catalog's products for other modules. Each operation keeps the business
 * rules, audit events and transactional behaviour of the corresponding catalog use case.
 */
public interface ProductCommandPort {

    /** Full product creation (plan limit, duplicate name, audit). */
    ProductView create(String tenantSchema, CreateProductInput input);

    /** Product update, including the OWNER-only DRAFT → ACTIVE promotion guard. */
    ProductView update(String tenantSchema, UpdateProductInput input);

    void archive(String tenantSchema, UUID productId, UUID actorId);

    void unarchive(String tenantSchema, UUID productId, UUID actorId);

    /** DRAFT product creation (POS / offline). */
    ProductView createDraft(String tenantSchema, CreateDraftProductInput input);

    /**
     * Persists an ACTIVE product as-is, with no stock and no threshold, and publishes the catalog's
     * {@code ProductCreatedEvent}. The caller owns the surrounding rules (duplicate name, plan
     * limit) — used by the inventory quick-add.
     */
    ProductView register(String tenantSchema, RegisterProductInput input);

    /**
     * Sets the minimum stock threshold of a product.
     *
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_NOT_FOUND
     * @throws IllegalArgumentException if the threshold is negative
     */
    ProductView changeMinimumThreshold(String tenantSchema, UUID productId, int threshold);
}
