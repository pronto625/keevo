package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowQueryPort;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import org.springframework.stereotype.Component;

/**
 * Story 20.6 — the resolved value of the tenant preference « create the clients and products of my documents »
 * ({@code invoicing_settings.derive_from_documents}): the owner's explicit choice when there is one, else on for a
 * tenant whose default sale flow is {@code DOCUMENT} (a Bref-like merchant) and off otherwise (POS, or no preferences
 * yet). The sale flow is read through {@code identity :: api} (one indexed read), only when no choice is stored.
 */
@Component
public class DeriveFromDocumentsPreference {

    private final TenantSaleFlowQueryPort saleFlows;

    public DeriveFromDocumentsPreference(TenantSaleFlowQueryPort saleFlows) {
        this.saleFlows = saleFlows;
    }

    /** {@code stored=null, flow=DOCUMENT → true}; {@code stored=null, flow=POS → false}; {@code stored=x → x}. */
    public boolean resolve(InvoicingSettings settings, String tenantSchema) {
        Boolean stored = settings == null ? null : settings.deriveFromDocuments();
        if (stored != null) {
            return stored;
        }
        return saleFlows.find(tenantSchema)
                .map(view -> view.saleFlow() == SaleFlow.DOCUMENT)
                .orElse(false);
    }
}
