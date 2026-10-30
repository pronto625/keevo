package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.CreditNoteLine;
import com.keevo.invoicing.invoice.domain.model.CreditNoteSelection;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.event.catalog.invoicing.CreditNoteIssuedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Issues a credit note — « avoir » (Story 14.7) on an issued or paid invoice, for all of it or some of its lines. In
 * one transaction, with the invoice row locked (credit notes of one invoice are serialised): the lines are computed
 * and checked against what is already credited, then the next number of the year's gapless credit-note sequence
 * ({@code AV-<yyyy>-<000001>}, calendar year in Africa/Douala) is allocated — a rollback gives it back. The invoice
 * itself is not touched.
 */
@Service
public class IssueCreditNoteUseCase {

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IssueCreditNoteUseCase(InvoiceRepository invoices, CreditNoteRepository creditNotes,
                                  ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.events = events;
        this.clock = clock;
    }

    /**
     * A manual credit note. {@code selection} {@code null} = a full credit note (everything still to credit).
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code INVOICE_NOT_FOUND}, {@code VALIDATION_ERROR}
     *         (reason), {@code INVOICE_INVALID_TRANSITION}, {@code CREDIT_NOTE_INVALID}
     */
    @Transactional
    public CreditNote issue(UUID invoiceId, String reason, List<CreditNoteSelection> selection, UUID actorId,
                            String tenantId) {
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow(InvoiceQueryUseCase::notFound);
        return issue(invoice, reason, selection, actorId, tenantId, null);
    }

    /**
     * Issues the credit note of {@code invoice} — already row-locked by the caller, in the caller's transaction.
     * {@code sourceEvent}: the idempotency key of an automatic credit note, {@code null} for a manual one.
     * {@code MANDATORY}: refuses to run without the caller's transaction (the lock and the number need it).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditNote issue(Invoice invoice, String reason, List<CreditNoteSelection> selection, UUID actorId,
                            String tenantId, String sourceEvent) {
        String why = CreditNote.requireReason(reason);
        List<CreditNoteLine> lines = CreditNote.creditLines(invoice, creditNotes.linesOfInvoice(invoice.id()),
                selection); // every refusal happens here, before a number is allocated
        Instant now = clock.instant();
        int year = LocalDate.ofInstant(now, KeevoZones.WAT).getYear();
        CreditNote note = CreditNote.issue(UUID.randomUUID(), CreditNote.number(year, creditNotes.nextNumber(year)),
                invoice, why, lines, actorId, now, sourceEvent);
        creditNotes.insert(note);
        events.publishEvent(new CreditNoteIssuedEvent(note.id(), note.number(), invoice.id(), invoice.number(),
                note.totals().totalTtc(), actorId, tenantId, now));
        return note;
    }
}
