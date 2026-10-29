package com.keevo.messaging.api;

/** Pending-draft badge counter (Story 2.4 AC10, published in 12.2e). */
public interface DraftValidationQueryPort {

    /**
     * Unacknowledged drafts of the tenant. No authorisation here: the caller (the catalog's drafts
     * counter endpoint) restricts it to the OWNER.
     */
    long countPending(String tenantSchema);
}
