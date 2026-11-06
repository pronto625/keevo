package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.QuoteConvertedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.3 — converts a quote into a new, issued invoice (REST {@code POST /invoices/{quoteId}/convert}, sync
 * {@code CONVERT_QUOTE}). Application service over the two rows, in one transaction:
 * <ol>
 *   <li>the quote is row-locked ({@code FOR UPDATE}): concurrent conversions are serialised;</li>
 *   <li>already {@code CONVERTED} → its invoice is returned as it is (idempotent: no second number, no second quota
 *       unit); anything but a DRAFT quote → {@code INVOICE_INVALID_TRANSITION}; an incomplete quote →
 *       {@code INVOICE_NOT_ISSUABLE}; {@code expectedUpdatedAt} not the quote's → {@code OPTIMISTIC_LOCK}; amounts the
 *       current VAT settings would change → 409 (the quote must be saved again first);</li>
 *   <li>the new invoice ({@code invoiceId} given by the device, else a new id) is inserted as a draft with the quote's
 *       client and lines and {@code source_quote_id}, then issued by the regular user issue path
 *       ({@link InvoiceLifecycleUseCase#issue(UUID, UUID, String)}: DOCUMENTS quota counted, gapless {@code FAC-}
 *       number, issue date = today in Africa/Douala, seller snapshot, {@code InvoiceIssuedEvent}) — a refusal
 *       ({@code QUOTA_EXCEEDED}, {@code INVOICE_NOT_ISSUABLE}) rolls everything back, the quote stays a DRAFT;</li>
 *   <li>the quote becomes {@code CONVERTED} with the link to the invoice.</li>
 * </ol>
 * The unique index on {@code source_quote_id} is the last guard: two conversions racing past the lock can never make
 * two invoices (the insert answers false, the existing invoice is returned). Cost: constant (1 locked read, the issue path's queries, 1 insert + 1 batch of lines, 1 update).
 */
@Service
public class ConvertQuoteUseCase {

    /** The invoice of the conversion; {@code created} = false when the quote had already been converted. */
    public record Conversion(Invoice invoice, Invoice quote, boolean created) {
    }

    private final InvoiceRepository invoices;
    private final InvoicingSettingsRepository settings;
    private final InvoiceLifecycleUseCase lifecycle;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConvertQuoteUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                               InvoiceLifecycleUseCase lifecycle, ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.settings = settings;
        this.lifecycle = lifecycle;
        this.events = events;
        this.clock = clock;
    }

    /** Story 20.3 — the French refusal of a conversion whose amounts the current VAT settings would change. */
    public static final String VAT_SETTINGS_CHANGED = "Les réglages de TVA ont changé depuis ce devis : "
            + "enregistrez-le à nouveau pour mettre à jour les montants, puis convertissez-le.";

    /** Story 20.5 — the French refusal of a conversion whose quote carries a due date already passed. */
    public static final String QUOTE_DUE_DATE_PASSED =
            "La date d'échéance de ce devis est passée : modifiez-la avant de le convertir.";

    @Transactional
    public Conversion convert(UUID quoteId, UUID invoiceId, UUID actorId, String tenantId) {
        return convert(quoteId, invoiceId, null, actorId, tenantId);
    }

    /**
     * @param expectedUpdatedAt the {@code updatedAt} of the quote the user converts ({@code null} = no check): the
     *                          quote changed since → 409 {@code OPTIMISTIC_LOCK}, nothing written. Checked after the
     *                          replay answer, so a replayed conversion still returns its invoice.
     */
    @Transactional
    public Conversion convert(UUID quoteId, UUID invoiceId, java.time.Instant expectedUpdatedAt, UUID actorId,
                              String tenantId) {
        Invoice quote = invoices.findByIdForUpdate(quoteId).orElseThrow(InvoiceQueryUseCase::notFound);
        if (quote.isQuote() && quote.status() == InvoiceStatus.CONVERTED) {
            return replayed(quote);
        }
        quote.assertConvertible(); // kind, status, completeness — before any write, number or quota
        ManageDraftInvoiceUseCase.assertFresh(quote, expectedUpdatedAt);
        // Story 20.5 — the invoice is issued today: a due date already passed would be refused at issue
        java.time.LocalDate dueDate = quote.terms().dueDate();
        if (dueDate != null && dueDate.isBefore(java.time.LocalDate.ofInstant(clock.instant(),
                com.keevo.shared.domain.time.KeevoZones.WAT))) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION, QUOTE_DUE_DATE_PASSED,
                    Map.of("status", "devis"));
        }
        UUID newId = invoiceId != null ? invoiceId : UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        InvoicingSettings current = settings.find()
                .orElseGet(() -> InvoicingSettings.defaults(settings.countryVatRate()));
        Invoice draft = ManageDraftInvoiceUseCase.valid(() -> quote.conversionDraft(newId, current, actorId, now));
        if (!draft.totals().equals(quote.totals())) {
            // the client accepted the quote's amounts: never issue other ones silently
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION, VAT_SETTINGS_CHANGED,
                    Map.of("status", "devis"));
        }
        if (!invoices.insertConversion(draft)) {
            // the quote was converted by another transaction past the lock (uq_invoices_source_quote): its invoice;
            // otherwise the id chosen by the device belongs to another document
            Optional<Invoice> converted = invoices.findBySourceQuote(quoteId);
            if (converted.isPresent()) {
                return new Conversion(converted.get(), invoices.findById(quoteId).orElse(quote), false);
            }
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, ID_TAKEN, Map.of("invoiceId", ID_TAKEN));
        }
        Invoice issued = lifecycle.issue(newId, actorId, tenantId);
        Invoice converted = quote.converted(issued.id(), now);
        invoices.markConverted(converted);
        events.publishEvent(new QuoteConvertedEvent(quote.id(), quote.quoteReference(), issued.id(), issued.number(),
                issued.client().name(), issued.totals().totalTtc(), actorId, tenantId, now));
        return new Conversion(issued, converted, true);
    }

    private static final String ID_TAKEN = "Cet identifiant de facture est déjà utilisé par un autre document";

    private Conversion replayed(Invoice quote) {
        Invoice existing = invoices.findById(quote.convertedInvoiceId()).orElseThrow(InvoiceQueryUseCase::notFound);
        return new Conversion(existing, quote, false);
    }
}
