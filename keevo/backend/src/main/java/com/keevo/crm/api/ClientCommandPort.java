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

    /**
     * Story 21.6 — the client's own answer on WhatsApp: STOP ({@code optedOut}) sets {@code marketing_opt_out_at} on
     * every client of the company with this number (any store), START clears it; the team's consent is never touched.
     * {@code updated_at} moves so the devices pull the change. One statement on {@code idx_clients_phone_normalized}.
     *
     * @param phoneE164 {@code +} and digits
     * @return the clients changed
     */
    int setMarketingOptOutByPhone(String tenantSchema, String phoneE164, boolean optedOut, java.time.Instant at);
}
