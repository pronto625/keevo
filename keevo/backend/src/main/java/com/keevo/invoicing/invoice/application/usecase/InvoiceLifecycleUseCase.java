package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.event.catalog.invoicing.InvoiceCancelledEvent;
import com.keevo.shared.event.catalog.invoicing.InvoiceIssuedEvent;
import com.keevo.shared.event.catalog.invoicing.InvoicePaidEvent;
import com.keevo.shared.capability.business.CompanyIdentity;
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
    private final CompanyIdentity companyIdentity;
    private final Clock clock;
    private final CreditNoteRepository creditNotes;

    public InvoiceLifecycleUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                   ApplicationEventPublisher events, CompanyIdentity companyIdentity, Clock clock,
                                   CreditNoteRepository creditNotes) {
        this.creditNotes = creditNotes;
        this.invoices = invoices;
        this.settings = settings;
        this.events = events;
        this.companyIdentity = companyIdentity;
        this.clock = clock;
    }

    /** {@code FAC-2026-000001}. */
    public static String number(int year, long sequence) {
        return String.format("FAC-%04d-%06d", year, sequence);
    }

    @Transactional
    public Invoice issue(UUID id, UUID actorId, String tenantId) {
        return issue(id, actorId, tenantId, false);
    }

    /**
     * Story 14.4 — {@code pricesIncludeVat} forces the seller settings to VAT-inclusive prices for this invoice (an
     * automatic invoice of a sale: its prices are what the client paid, whatever the setting). Same numbering, same
     * events as a manual issue.
     */
    @Transactional
    public Invoice issue(UUID id, UUID actorId, String tenantId, boolean pricesIncludeVat) {
        Invoice draft = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        InvoicingSettings found = settings.find().orElseGet(InvoicingSettings::defaults);
        InvoicingSettings stored = pricesIncludeVat ? withPricesIncludingVat(found) : found;
        // Story 14.2 — no legal name typed: the company display name (designation › shop name) is printed instead
        // (non-blocking; the invoice carries a compliance warning).
        boolean nameFromDisplay = stored.legalName() == null;
        InvoicingSettings seller = nameFromDisplay
                ? withLegalName(stored, companyIdentity.displayName(tenantId)) : stored;
        boolean hasLogo = companyIdentity.hasLogo(tenantId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KeevoZones.WAT);
        draft.assertIssuable(seller); // throws the precise error before any number is allocated
        Invoice issued = ManageDraftInvoiceUseCase.valid(() -> draft.issue(
                number(today.getYear(), invoices.nextNumber(today.getYear())), today, seller, now))
                .withSellerIdentity(nameFromDisplay, hasLogo);
        invoices.issue(issued);
        events.publishEvent(new InvoiceIssuedEvent(issued.id(), issued.number(), issued.client().name(),
                issued.totals().totalTtc(), actorId, tenantId, now));
        return issued;
    }

    /** Story 14.4 — the settings with VAT-inclusive prices (automatic invoice of a sale). */
    static InvoicingSettings withPricesIncludingVat(InvoicingSettings s) {
        return new InvoicingSettings(s.legalName(), s.address(), s.phone(), s.niu(), s.rccm(), s.vatRegistered(),
                s.vatRate(), true, s.paymentDetails(), s.footer());
    }

    /** The settings with the company display name as legal name (Story 14.2 fallback). */
    static InvoicingSettings withLegalName(InvoicingSettings s, String legalName) {
        return new InvoicingSettings(legalName, s.address(), s.phone(), s.niu(), s.rccm(), s.vatRegistered(),
                s.vatRate(), s.pricesIncludeVat(), s.paymentDetails(), s.footer());
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
        // Story 14.7: an invoice with a credit note is no longer cancellable (its rest is credited instead)
        Invoice cancelled = issued.cancel(reason, now,
                issued.status() == InvoiceStatus.ISSUED && creditNotes.existsForInvoice(id));
        invoices.markCancelled(cancelled);
        events.publishEvent(new InvoiceCancelledEvent(cancelled.id(), cancelled.number(), cancelled.client().name(),
                cancelled.totals().totalTtc(), cancelled.cancellationReason(), actorId, tenantId, now));
        return cancelled;
    }
}
