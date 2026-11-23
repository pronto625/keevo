package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientConsentPort;
import com.keevo.crm.contact.application.usecase.SetClientMarketingConsentUseCase;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.shared.infrastructure.persistence.TenantContext;

import java.util.UUID;

/**
 * Story 22.19 (D8) — the {@link ClientConsentPort} capability, created and provided by {@code CrmModule} (reachable
 * only through {@code CapabilityRegistry.find}): the consent use case with the system access, as
 * {@code ClientCommandPort#setMarketingConsent}.
 */
public final class ClientConsentAdapter implements ClientConsentPort {

    private final SetClientMarketingConsentUseCase consent;

    public ClientConsentAdapter(SetClientMarketingConsentUseCase consent) {
        this.consent = consent;
    }

    @Override
    public void giveMarketingConsent(String tenantSchema, UUID clientId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        consent.execute(clientId, true, actorId, tenantSchema, ClientAccess.SYSTEM);
    }
}
