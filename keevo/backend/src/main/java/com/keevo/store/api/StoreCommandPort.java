package com.keevo.store.api;

import java.util.UUID;

/**
 * Store commands for other modules (Story 12.2d — the sync push). Every method joins the caller's
 * transaction (the store use cases are REQUIRED); none opens its own.
 */
public interface StoreCommandPort {

    /**
     * Validates a store name (required, 2–100 characters once trimmed) and returns it trimmed —
     * the rule of the REST DTO and of store creation.
     *
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_ERROR
     */
    String validateName(String name);

    /**
     * Creates an active store under the id chosen on the device, idempotently. Takes the per-tenant
     * store-limit lock (same key as the REST creation, held until the caller's transaction ends),
     * then: id already present → no-op; else checks the plan's store limit
     * ({@code PLAN_LIMIT_EXCEEDED}, {@code SUBSCRIPTION_NOT_FOUND}) and the warehouse uniqueness
     * ({@code WAREHOUSE_ALREADY_EXISTS}) before the insert. Publishes no event (same as the offline
     * creation always did).
     *
     * @return true if the store was created, false if a store with that id already existed
     */
    boolean createWithId(String tenantSchema, CreateStoreWithIdInput input);

    /** Runs the store update use case (joins the caller's transaction; publishes its event). @return the updated store */
    StoreView update(String tenantSchema, UpdateStoreInput input);

    /** Runs the store deactivation use case (joins the caller's transaction; publishes its event). */
    void deactivate(String tenantSchema, UUID storeId, UUID actorId);
}
