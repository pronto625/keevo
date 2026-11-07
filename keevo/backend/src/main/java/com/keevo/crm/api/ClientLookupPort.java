package com.keevo.crm.api;

import com.keevo.shared.module.Capability;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional capability of the {@code crm} module (Story 13.6): look a client up from another module without a hard
 * dependency on {@code crm}. Never inject it: resolve it per tenant with
 * {@code CapabilityRegistry.find(ClientLookupPort.class, tenantSchema)}, which is empty while {@code crm} is off
 * for the tenant — the consumer then degrades (e.g. shows the free-text name it already has).
 *
 * <p>Same tenant convention as the other {@code crm.api} ports: {@code tenantSchema} must be the current tenant
 * context ({@link IllegalStateException} otherwise). Read-only; archived clients are included (a past sale still
 * shows its client).
 */
public interface ClientLookupPort extends Capability {

    /** The client's display name; empty when no client has this id. */
    Optional<String> displayName(String tenantSchema, UUID clientId);

    /** Whether a client has this id. */
    boolean exists(String tenantSchema, UUID clientId);

    /** Story 14.3 — the client with this id (archived included); empty when unknown or {@code clientId} is null. */
    Optional<ClientSummary> find(String tenantSchema, UUID clientId);

    /**
     * Story 14.3 — non-archived clients whose name or phone contains {@code query} (case-insensitive; every
     * non-archived client when the query is blank), sorted by name, at most {@code limit} (clamped to 1–50).
     */
    List<ClientSummary> search(String tenantSchema, String query, int limit);

    /**
     * Story 20.6 — the CRM client of an issued document, found or created (Bref: issuing a sale records its customer).
     * The client is looked up by E.164 phone in the acting user's establishment (OWNER: the oldest active store;
     * store member: its own store), then among the legacy clients without store; several → the oldest. A found client
     * only gets its empty email / RCCM / NIU filled from the document (never its name). None → created in that store
     * (audited as a creation by {@code actorId}). Concurrent calls for the same phone and store are serialised.
     *
     * <p>Never throws for a business reason (unparseable phone, member without store, nothing to create from): the
     * answer is then empty and the caller keeps its free-text client. Joins the caller's transaction (required).
     *
     * @return the client id to link, empty when no derivation is possible
     */
    java.util.Optional<UUID> findOrCreateForDocument(String tenantSchema, DocumentClientInput client, UUID actorId);
}
