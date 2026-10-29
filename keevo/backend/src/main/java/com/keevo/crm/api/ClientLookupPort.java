package com.keevo.crm.api;

import com.keevo.shared.module.Capability;

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
}
