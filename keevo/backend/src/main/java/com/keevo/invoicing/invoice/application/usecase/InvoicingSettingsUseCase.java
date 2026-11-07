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
    private final DeriveFromDocumentsPreference derivePreference;

    public InvoicingSettingsUseCase(InvoicingSettingsRepository settings, ApplicationEventPublisher events,
                                    Clock clock, DeriveFromDocumentsPreference derivePreference) {
        this.settings = settings;
        this.events = events;
        this.clock = clock;
        this.derivePreference = derivePreference;
    }

    /** Story 20.6 — the settings as served: stored values and the resolved derivation preference. */
    public record SettingsView(InvoicingSettings settings, boolean deriveFromDocuments) {
    }

    /** Story 20.6 — {@link #current()} with the resolved derivation preference (one sale-flow read when unset). */
    @Transactional(readOnly = true)
    public SettingsView currentView(String tenantSchema) {
        InvoicingSettings current = current();
        return new SettingsView(current, derivePreference.resolve(current, tenantSchema));
    }

    /**
     * Story 20.6 — saves {@code updated}; its {@code deriveFromDocuments} {@code null} (a body without the field: V1
     * apps) keeps the stored choice. Answers the saved settings with the resolved preference.
     */
    @Transactional
    public SettingsView updateView(InvoicingSettings updated, UUID actorId, String tenantId) {
        InvoicingSettings toSave = updated.deriveFromDocuments() != null ? updated
                : updated.withDeriveFromDocuments(settings.find().map(InvoicingSettings::deriveFromDocuments)
                        .orElse(null));
        InvoicingSettings saved = update(toSave, actorId, tenantId);
        return new SettingsView(saved, derivePreference.resolve(saved, tenantId));
    }

    /** The saved profile, or the defaults when the owner never saved one. */
    @Transactional(readOnly = true)
    public InvoicingSettings current() {
        return settings.find().orElseGet(() -> InvoicingSettings.defaults(settings.countryVatRate()));
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
