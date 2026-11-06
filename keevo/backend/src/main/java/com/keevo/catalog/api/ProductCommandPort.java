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

    /**
     * Restores an archived product (17.10 limit check for an ACTIVE one). Story 17.13: {@code false} when the product
     * was no longer archived (no-op, no audit).
     *
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_NOT_FOUND, PLAN_LIMIT_EXCEEDED
     */
    boolean unarchive(String tenantSchema, UUID productId, UUID actorId);

    /**
     * Story 17.13 — same restore, but at the plan limit the product stays archived and {@code false} is answered
     * instead of throwing (the caller's transaction stays usable).
     */
    boolean unarchiveIfAllowed(String tenantSchema, UUID productId, UUID actorId);

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

    /**
     * Story 17.12 — turns the product's stock tracking on or off (all variants, all stores). OWNER only; a real
     * change is audited (« Suivi du stock activé / désactivé »); the same value is a no-op (conditional update:
     * of two concurrent identical calls, only one reports {@code changed}). Stock levels are never touched here:
     * disabling freezes them, re-enabling resumes from them (no level = 0).
     *
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_NOT_FOUND, FORBIDDEN (not the owner),
     *         MODULE_NOT_ENTITLED (inventory DISABLED), TRANSFER_IN_TRANSIT (disable with a transfer in transit)
     */
    StockTrackingUpdate setStockTracking(String tenantSchema, UUID productId, boolean tracked, UUID actorId, String actorRole);

    /**
     * Story 20.1 — merges {@code sourceId} into {@code targetId} (owner command, one transaction, both rows locked):
     * the source is archived and redirected, products merged into it follow, the target inherits its barcode when it
     * has none; history is untouched. Audited « Produit fusionné ».
     *
     * @return the target product
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_ERROR (same product), PRODUCT_NOT_FOUND,
     *         PRODUCT_MERGE_INVALID (already merged, archived target, source holding stock)
     */
    ProductView merge(String tenantSchema, UUID sourceId, UUID targetId, UUID actorId);
}
