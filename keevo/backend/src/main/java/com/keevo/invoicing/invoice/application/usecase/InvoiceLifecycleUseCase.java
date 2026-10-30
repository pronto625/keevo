package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.event.catalog.invoicing.InvoiceCancelledEvent;
import com.keevo.shared.event.catalog.invoicing.InvoiceIssuedEvent;
import com.keevo.shared.event.catalog.invoicing.InvoicePaidEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Issues, pays and cancels invoices (Story 14.1). Issuing assigns, in one transaction with the invoice row locked:
 * the next number of the year's gapless sequence ({@code FAC-<yyyy>-<000001>}, calendar year in Africa/Douala), the
 * issue date and the seller snapshot — a rollback gives the number back, so the sequence never has a gap.
 */
@Service
public class InvoiceLifecycleUseCase {

    private final InvoiceRepository invoices;
    private final InvoicingSettingsRepository settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public InvoiceLifecycleUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                   ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    /** {@code FAC-2026-000001}. */
    public static String number(int year, long sequence) {
        return String.format("FAC-%04d-%06d", year, sequence);
    }

    @Transactional
    public Invoice issue(UUID id, UUID actorId, String tenantId) {
        Invoice draft = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        InvoicingSettings seller = settings.find().orElseGet(InvoicingSettings::defaults);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KeevoZones.WAT);
        draft.assertIssuable(seller); // throws the precise error before any number is allocated
        Invoice issued = ManageDraftInvoiceUseCase.valid(() -> draft.issue(
                number(today.getYear(), invoices.nextNumber(today.getYear())), today, seller, now));
        invoices.issue(issued);
        events.publishEvent(new InvoiceIssuedEvent(issued.id(), issued.number(), issued.client().name(),
                issued.totals().totalTtc(), actorId, tenantId, now));
        return issued;
    }

    @Transactional
    public Invoice pay(UUID id, String paymentMethod, UUID actorId, String tenantId) {
        Invoice issued = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        Instant now = clock.instant();
        Invoice paid = issued.pay(paymentMethod, now);
        invoices.markPaid(paid);
        events.publishEvent(new InvoicePaidEvent(paid.id(), paid.number(), paid.client().name(),
                paid.totals().totalTtc(), paid.paymentMethod(), actorId, tenantId, now));
        return paid;
    }

    @Transactional
    public Invoice cancel(UUID id, String reason, UUID actorId, String tenantId) {
        Invoice issued = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        Instant now = clock.instant();
        Invoice cancelled = issued.cancel(reason, now);
        invoices.markCancelled(cancelled);
        events.publishEvent(new InvoiceCancelledEvent(cancelled.id(), cancelled.number(), cancelled.client().name(),
                cancelled.totals().totalTtc(), cancelled.cancellationReason(), actorId, tenantId, now));
        return cancelled;
    }
}
