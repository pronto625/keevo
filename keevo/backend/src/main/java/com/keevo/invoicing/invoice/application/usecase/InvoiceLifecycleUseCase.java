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
import com.keevo.shared.event.catalog.invoicing.QuoteCancelledEvent;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
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
 *
 * <p>Story 19.8 — a user's issue (REST {@code POST /invoices/{id}/issue}, sync {@code ISSUE_INVOICE}) counts one
 * {@code DOCUMENTS} unit of the plan's quota, in the same transaction, before numbering: a refusal
 * ({@code QUOTA_EXCEEDED}) leaves the draft a draft and allocates no number. The automatic invoice of a POS sale
 * ({@link #issue(UUID, UUID, String, boolean)}) is never counted, nor blocked.
 *
 * <p>Story 20.3 — a quote is never issued nor paid ({@code INVOICE_INVALID_TRANSITION}, « Un devis doit être converti
 * en facture »); {@link #cancel} also cancels a DRAFT quote (no number). The conversion of a quote
 * ({@link ConvertQuoteUseCase}) issues its new invoice through {@link #issue(UUID, UUID, String)}: same quota, same
 * numbering, same events.
 *
 * <p>Story 20.6 — a counted issue (user issue, sync issue, quote conversion) first derives the invoice's CRM client
 * and catalogue products ({@link DocumentDerivationService}); the POS bridge never does.
 */
@Service
public class InvoiceLifecycleUseCase {

    private final InvoiceRepository invoices;
    private final InvoicingSettingsRepository settings;
    private final ApplicationEventPublisher events;
    private final CompanyIdentity companyIdentity;
    private final Clock clock;
    private final CreditNoteRepository creditNotes;
    private final UsageQuotaPort quotas;
    private final InvoicePaymentUseCase payments;
    private final DocumentDerivationService derivation;

    public InvoiceLifecycleUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                   ApplicationEventPublisher events, CompanyIdentity companyIdentity, Clock clock,
                                   CreditNoteRepository creditNotes, UsageQuotaPort quotas,
                                   InvoicePaymentUseCase payments, DocumentDerivationService derivation) {
        this.payments = payments;
        this.derivation = derivation;
        this.creditNotes = creditNotes;
        this.quotas = quotas;
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

    /** A user's issue: counts one {@code DOCUMENTS} unit of the plan's quota (Story 19.8). */
    @Transactional
    public Invoice issue(UUID id, UUID actorId, String tenantId) {
        return issue(id, actorId, tenantId, false, true);
    }

    /**
     * Story 14.4 — {@code pricesIncludeVat} forces the seller settings to VAT-inclusive prices for this invoice (an
     * automatic invoice of a sale: its prices are what the client paid, whatever the setting). Same numbering, same
     * events as a manual issue.
     */
    @Transactional
    public Invoice issue(UUID id, UUID actorId, String tenantId, boolean pricesIncludeVat) {
        return issue(id, actorId, tenantId, pricesIncludeVat, false);
    }

    private Invoice issue(UUID id, UUID actorId, String tenantId, boolean pricesIncludeVat, boolean counted) {
        Invoice locked = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        InvoicingSettings found = settings.find()
                .orElseGet(() -> InvoicingSettings.defaults(settings.countryVatRate()));
        InvoicingSettings stored = pricesIncludeVat ? withPricesIncludingVat(found) : found;
        // Story 14.2 — no legal name typed: the company display name (designation › shop name) is printed instead
        // (non-blocking; the invoice carries a compliance warning).
        boolean nameFromDisplay = stored.legalName() == null;
        InvoicingSettings seller = nameFromDisplay
                ? withLegalName(stored, companyIdentity.displayName(tenantId)) : stored;
        boolean hasLogo = companyIdentity.hasLogo(tenantId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KeevoZones.WAT);
        locked.assertIssuable(seller); // throws the precise error before any number is allocated
        locked.terms().assertDueDateFrom(today); // Story 20.5 — a due date never precedes the issue date
        if (counted) { // 19.8: before numbering, same transaction — a refusal keeps the draft and the sequence
            quotas.assertAndIncrementForSchema(tenantId, UsageMetric.DOCUMENTS, 1);
        }
        // Story 20.6 — after the cheap checks (a refused issue derives nothing): a user's issue (never the POS
        // bridge) records its client and products in the CRM / catalogue when the tenant wants it; same transaction
        Invoice draft = counted ? derivation.derive(locked, found, seller, actorId, tenantId, now) : locked;
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
                s.vatRate(), true, s.paymentDetails(), s.footer(), s.deriveFromDocuments());
    }

    /** The settings with the company display name as legal name (Story 14.2 fallback). */
    static InvoicingSettings withLegalName(InvoicingSettings s, String legalName) {
        return new InvoicingSettings(legalName, s.address(), s.phone(), s.niu(), s.rccm(), s.vatRegistered(),
                s.vatRate(), s.pricesIncludeVat(), s.paymentDetails(), s.footer(), s.deriveFromDocuments());
    }

    @Transactional
    public Invoice pay(UUID id, String paymentMethod, UUID actorId, String tenantId) {
        return pay(id, paymentMethod, null, actorId, tenantId);
    }

    /**
     * Story 15.5 — {@code paidOn}: the day the client paid ({@code null} = now), see {@link Invoice#paymentInstant}. Story 20.4:
     * settles the whole invoice — every scheduled instalment paid on that day, a payment of the balance added, state and
     * status PAID ({@link InvoicePaymentUseCase#settleAll}); same contract for V1 apps.
     */
    @Transactional
    public Invoice pay(UUID id, String paymentMethod, LocalDate paidOn, UUID actorId, String tenantId) {
        Invoice issued = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        return payments.settleAll(issued, paymentMethod, paidOn, actorId, tenantId);
    }

    @Transactional
    public Invoice cancel(UUID id, String reason, UUID actorId, String tenantId) {
        Invoice issued = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        Instant now = clock.instant();
        // Story 14.7: an invoice with a credit note is no longer cancellable (its rest is credited instead)
        // Story 20.4: nor one with a payment received (credited instead)
        boolean issuedInvoice = issued.status() == InvoiceStatus.ISSUED && !issued.isQuote();
        Invoice cancelled = issued.cancel(reason, now, issuedInvoice && creditNotes.existsForInvoice(id),
                issuedInvoice && payments.hasPayments(id));
        invoices.markCancelled(cancelled);
        if (cancelled.isQuote()) { // Story 20.3 — a DRAFT quote, never numbered
            events.publishEvent(new QuoteCancelledEvent(cancelled.id(), cancelled.quoteReference(),
                    cancelled.client().name(), cancelled.totals().totalTtc(), cancelled.cancellationReason(), actorId,
                    tenantId, now));
            return cancelled;
        }
        events.publishEvent(new InvoiceCancelledEvent(cancelled.id(), cancelled.number(), cancelled.client().name(),
                cancelled.totals().totalTtc(), cancelled.cancellationReason(), actorId, tenantId, now));
        return cancelled;
    }
}
