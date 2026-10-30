package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The seller profile of the current tenant (single row, Story 14.1). */
public interface InvoicingSettingsRepository {

    Optional<InvoicingSettings> find();

    void save(InvoicingSettings settings, UUID updatedBy, Instant now);
}
