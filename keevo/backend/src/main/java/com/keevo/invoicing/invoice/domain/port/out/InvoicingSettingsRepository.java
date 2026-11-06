package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The seller profile of the current tenant (single row, Story 14.1). */
public interface InvoicingSettingsRepository {

    Optional<InvoicingSettings> find();

    void save(InvoicingSettings settings, UUID updatedBy, Instant now);

    /**
     * Story 20.5 — the VAT rate of the seller's country (the country of the tenant's oldest active store, Bref's
     * table): the rate of the settings before the owner saves them ({@code null} = 19.25 %).
     */
    BigDecimal countryVatRate();
}
