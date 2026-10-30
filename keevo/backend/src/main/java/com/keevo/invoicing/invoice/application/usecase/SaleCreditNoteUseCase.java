package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.SaleInvoiceStateRepository;
import com.keevo.shared.event.catalog.sale.SaleCancelledEvent;
import com.keevo.shared.event.catalog.sale.SaleCorrectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 14.7 — the accounting documents that follow a cancelled or corrected sale (POS → Invoicing bridge, 14.4). The
 * invoice of the sale is never changed:
 * <ul>
 *   <li><b>sale cancelled</b> → a full credit note « Annulation de la vente » on the sale's active invoice (none →
 *       nothing to credit); the sale is marked cancelled, so a late {@code sale.completed} / {@code sale.corrected}
 *       issues no invoice;</li>
 *   <li><b>sale corrected</b> → in <b>one</b> transaction: a full credit note « Correction de la vente » on the active
 *       invoice (none when it is already fully credited), the invoice marked superseded, then a new invoice issued and
 *       paid from the corrected lines (same mapping and rules as 14.4). Without an active invoice (the correction was
 *       delivered before the completion, or the sale was never invoiced) the invoice is issued from the corrected
 *       lines; the late {@code sale.completed} then finds it and does nothing.</li>
 * </ul>
 *
 * <p>Sale events can be delivered out of order: every delivery of a sale first locks its {@code sale_invoice_states}
 * row (deliveries of one sale are serialised). A correction whose {@code occurredAt} is not after the last correction
 * applied (a replay, or an older correction retried late) is skipped. The automatic credit note also carries its key
 * ({@code cancel:<saleId>}, {@code correct:<saleId>:<occurredAt epoch millis>}, unique). Runs in its own transaction
 * ({@code REQUIRES_NEW}).
 */
@Service
public class SaleCreditNoteUseCase {

    /** What happened to the sale's documents. */
    public enum Outcome {
        /** The credit note (and, for a correction, the new invoice) was issued. */
        DONE,
        /** Already done for this sale event (replay): nothing done. */
        ALREADY_DONE,
        /** Nothing to credit or to invoice (no active invoice, sale cancelled, nothing left to credit): nothing done. */
        NOTHING_TO_DO
    }

    static final String CANCEL_REASON = "Annulation de la vente";
    static final String CORRECTION_REASON = "Correction de la vente";

    private static final Logger log = LoggerFactory.getLogger(SaleCreditNoteUseCase.class);

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final IssueCreditNoteUseCase issueCreditNote;
    private final InvoiceSaleUseCase invoiceSale;
    private final Clock clock;
    private final SaleInvoiceStateRepository saleStates;

    public SaleCreditNoteUseCase(InvoiceRepository invoices, CreditNoteRepository creditNotes,
                                 IssueCreditNoteUseCase issueCreditNote, InvoiceSaleUseCase invoiceSale, Clock clock,
                                 SaleInvoiceStateRepository saleStates) {
        this.saleStates = saleStates;
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.issueCreditNote = issueCreditNote;
        this.invoiceSale = invoiceSale;
        this.clock = clock;
    }

    /** {@code cancel:<saleId>}. */
    public static String cancelKey(UUID saleId) {
        return "cancel:" + saleId;
    }

    /** {@code correct:<saleId>:<occurredAt epoch millis>}. */
    public static String correctionKey(SaleCorrectedEvent event) {
        return "correct:" + event.saleId() + ":" + event.occurredAt().toEpochMilli();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome cancelled(SaleCancelledEvent event) {
        UUID saleId = event.saleId();
        String key = cancelKey(saleId);
        SaleInvoiceStateRepository.State state = saleStates.lock(saleId);
        if (state.cancelledAt() == null) {
            saleStates.markCancelled(saleId, event.occurredAt() != null ? event.occurredAt() : clock.instant());
        }
        Optional<Invoice> active = invoices.findActiveBySourceSaleForUpdate(saleId);
        if (active.isEmpty()) {
            return Outcome.NOTHING_TO_DO; // a late sale.completed will see the sale cancelled
        }
        if (creditNotes.existsBySourceEvent(key)) {
            return Outcome.ALREADY_DONE;
        }
        if (!creditable(active.get(), event.tenantId())) {
            return Outcome.NOTHING_TO_DO;
        }
        issueCreditNote.issue(active.get(), CANCEL_REASON, null, event.actorId(), event.tenantId(), key);
        return Outcome.DONE;
    }

    /**
     * {@code event.lines()}, {@code totalAmount()} and {@code occurredAt()} are checked non-null by the caller.
     *
     * @throws InvoiceSaleUseCase.SaleInvoiceRefused the corrected content is refused by the invoice domain: the whole
     *                                               transaction rolls back (nothing written)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome corrected(SaleCorrectedEvent event) {
        UUID saleId = event.saleId();
        String key = correctionKey(event);
        String tenant = event.tenantId();
        List<LineInput> lines;
        try {
            lines = SaleInvoiceMapper.lines(event.lines(), event.totalAmount());
        } catch (IllegalArgumentException | ArithmeticException e) {
            log.warn("[invoicing] corrected sale of tenant {} not re-invoiced: {}", tenant, e.getMessage());
            return Outcome.NOTHING_TO_DO;
        }
        // stored at microsecond precision: compare at that precision
        Instant at = event.occurredAt().truncatedTo(ChronoUnit.MICROS);
        SaleInvoiceStateRepository.State state = saleStates.lock(saleId);
        if (state.cancelledAt() != null) {
            log.info("[invoicing] corrected sale of tenant {} not re-invoiced: sale cancelled", tenant);
            return Outcome.NOTHING_TO_DO;
        }
        if (state.lastCorrectionAt() != null && !at.isAfter(state.lastCorrectionAt())) {
            log.info("[invoicing] correction of a sale of tenant {} skipped: not after the last one applied", tenant);
            return Outcome.ALREADY_DONE; // a replay, or an older correction delivered late
        }
        if (creditNotes.existsBySourceEvent(key)) {
            return Outcome.ALREADY_DONE;
        }
        saleStates.markCorrected(saleId, at);
        Optional<Invoice> active = invoices.findActiveBySourceSaleForUpdate(saleId);
        if (active.isPresent()) {
            Invoice invoice = active.get();
            if (invoice.status() != InvoiceStatus.ISSUED && invoice.status() != InvoiceStatus.PAID) {
                log.info("[invoicing] sale invoice {} of tenant {} not re-invoiced: {}", invoice.number(), tenant,
                        invoice.status());
                return Outcome.NOTHING_TO_DO;
            }
            Instant supersededAt = clock.instant();
            if (creditable(invoice, tenant)) {
                supersededAt = issueCreditNote.issue(invoice, CORRECTION_REASON, null, event.actorId(), tenant, key)
                        .issuedAt();
            } // already fully credited by hand: replaced without a new credit note
            invoices.markSuperseded(invoice.id(), supersededAt);
        }
        invoiceSale.paidInvoice(saleId, event.clientId(), event.paymentMode(), lines, event.actorId(), tenant);
        return Outcome.DONE;
    }

    /** Whether the sale event's documents exist (read outside any failed transaction, after a duplicate key). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isDone(String sourceEvent) {
        return creditNotes.existsBySourceEvent(sourceEvent);
    }

    /** ISSUED or PAID with an amount still to credit (else logged at INFO). */
    private boolean creditable(Invoice invoice, String tenant) {
        if ((invoice.status() != InvoiceStatus.ISSUED && invoice.status() != InvoiceStatus.PAID)
                || CreditNote.remainingTtc(invoice, creditNotes.linesOfInvoice(invoice.id())) == 0) {
            log.info("[invoicing] sale invoice {} of tenant {} not credited: {} or nothing left to credit",
                    invoice.number(), tenant, invoice.status());
            return false;
        }
        return true;
    }
}
