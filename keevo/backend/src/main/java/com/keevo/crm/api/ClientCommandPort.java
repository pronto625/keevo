package com.keevo.crm.api;

import java.util.UUID;

/**
 * Write access to the crm's clients for other modules. Validation (name, E.164 phone, email),
 * audit events and transactions are those of the crm.
 */
public interface ClientCommandPort {

    /**
     * Story 20.13 — creates a client (crm rules: store of the new client, E.164 phone, audit event with the actor);
     * {@code duplicates} = other non-archived clients of the same store with the same phone (the DUPLICATE_PHONE
     * warning, never a refusal).
     */
    CreatedClient create(String tenantSchema, CreateClientInput input);

    ClientView update(String tenantSchema, UpdateClientInput input);

    /** Story 20.13 — records or withdraws the marketing consent (author = actor), audited by the crm. */
    ClientView setMarketingConsent(String tenantSchema, UUID clientId, boolean consent, UUID actorId);

    void archive(String tenantSchema, UUID clientId, UUID actorId);

    void unarchive(String tenantSchema, UUID clientId, UUID actorId);
}
