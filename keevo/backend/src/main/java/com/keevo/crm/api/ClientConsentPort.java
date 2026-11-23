package com.keevo.crm.api;

import com.keevo.shared.module.Capability;

import java.util.UUID;

/**
 * Story 22.19 (D8) — optional capability of the {@code crm} module: record the marketing consent a client gave in
 * another module (a quote accepted with « J'accepte de recevoir des offres »). Never inject it: resolve it per tenant
 * with {@code CapabilityRegistry.find(ClientConsentPort.class, tenantSchema)}, empty while {@code crm} is off.
 *
 * <p>Same tenant convention as the other {@code crm.api} ports ({@code tenantSchema} = the current tenant context).
 * Audited by the crm like a consent given in the client card; a client already consenting is left as it is.
 */
public interface ClientConsentPort extends Capability {

    void giveMarketingConsent(String tenantSchema, UUID clientId, UUID actorId);
}
