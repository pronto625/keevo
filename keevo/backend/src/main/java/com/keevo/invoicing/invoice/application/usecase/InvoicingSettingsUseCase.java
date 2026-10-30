package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.event.catalog.invoicing.InvoicingSettingsUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Reads and saves the tenant's seller profile (Story 14.1). Never changes an issued invoice (snapshotted). A save is
 * audited ({@link InvoicingSettingsUpdatedEvent}): it changes the legal identity / VAT printed on new invoices.
 */
@Service
public class InvoicingSettingsUseCase {

    private final InvoicingSettingsRepository settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public InvoicingSettingsUseCase(InvoicingSettingsRepository settings, ApplicationEventPublisher events,
                                    Clock clock) {
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    /** The saved profile, or the defaults when the owner never saved one. */
    @Transactional(readOnly = true)
    public InvoicingSettings current() {
        return settings.find().orElseGet(InvoicingSettings::defaults);
    }

    @Transactional
    public InvoicingSettings update(InvoicingSettings updated, UUID actorId, String tenantId) {
        Instant now = clock.instant();
        settings.save(updated, actorId, now);
        events.publishEvent(new InvoicingSettingsUpdatedEvent(updated.legalName(), updated.vatRegistered(), actorId,
                tenantId, now));
        return updated;
    }
}
