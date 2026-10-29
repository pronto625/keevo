package com.keevo.messaging.api;

import java.util.UUID;

/**
 * Tracking of DRAFT products awaiting owner validation (Story 2.4, published in 12.2e), driven by
 * the catalog: one pending record per draft, acknowledged when the owner promotes it. Joins the
 * caller's transaction.
 */
public interface DraftValidationCommandPort {

    /** Records a draft awaiting validation (badge counter). */
    void recordPending(String tenantSchema, PendingDraftInput input);

    /** Acknowledges the pending record of {@code productId}, if any (no-op otherwise). */
    void acknowledge(String tenantSchema, UUID productId);
}
