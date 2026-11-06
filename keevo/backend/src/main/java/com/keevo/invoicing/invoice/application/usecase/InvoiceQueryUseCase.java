package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads invoices of the current tenant (Story 14.1). */
@Service
public class InvoiceQueryUseCase {

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final InvoicePaymentRepository payments;
    private final Clock clock;

    public InvoiceQueryUseCase(InvoiceRepository invoices, CreditNoteRepository creditNotes,
                               InvoicePaymentRepository payments, Clock clock) {
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.payments = payments;
        this.clock = clock;
    }

    /**
     * Story 20.4 — the collection of each invoice (state, amounts, payments and instalments; legacy reading of an invoice
     * paid before 20.4), from one batched query for all of them ({@link InvoicePaymentRepository#ledgers}); the amount
     * due uses the credits already loaded ({@link #credits}).
     */
    @Transactional(readOnly = true)
    public Map<UUID, PaymentStateCalculator.Collection> collections(Collection<Invoice> list,
                                                                    Map<UUID, Credits> credits) {
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<UUID, InvoicePaymentRepository.Ledger> ledgers = payments.ledgers(list.stream().map(Invoice::id).toList());
        LocalDate today = LocalDate.ofInstant(clock.instant(), KeevoZones.WAT);
        Map<UUID, PaymentStateCalculator.Collection> out = new java.util.HashMap<>();
        for (Invoice invoice : list) {
            Credits c = credits.getOrDefault(invoice.id(), Credits.NONE);
            long credited = c.notes().stream().mapToLong(CreditNoteRepository.Summary::totalTtc).sum();
            InvoicePaymentRepository.Ledger ledger =
                    ledgers.getOrDefault(invoice.id(), InvoicePaymentRepository.Ledger.EMPTY);
            out.put(invoice.id(), PaymentStateCalculator.view(invoice, credited, ledger.storedState(), ledger.rows(),
                    today));
        }
        return out;
    }

    /**
     * Story 14.7 — the credit notes of an invoice (oldest first) and whether nothing remains to credit on it (the
     * domain predicate on remaining quantities, the one that refuses a further credit note).
     */
    public record Credits(List<CreditNoteRepository.Summary> notes, boolean fullyCredited) {

        public static final Credits NONE = new Credits(List.of(), false);
    }

    /** Story 14.7 — the credits of each invoice; an invoice without credit note is absent. */
    @Transactional(readOnly = true)
    public Map<UUID, Credits> credits(Collection<Invoice> invoices) {
        Map<UUID, List<CreditNoteRepository.Summary>> summaries =
                creditNotes.summaries(invoices.stream().map(Invoice::id).toList());
        if (summaries.isEmpty()) {
            return Map.of();
        }
        // Story 20.4 — the credited lines of the page in one query (no per-invoice query)
        Map<UUID, List<com.keevo.invoicing.invoice.domain.model.CreditNoteLine>> lines =
                creditNotes.linesOfInvoices(summaries.keySet());
        Map<UUID, Credits> credits = new java.util.HashMap<>();
        for (Invoice invoice : invoices) {
            List<CreditNoteRepository.Summary> notes = summaries.get(invoice.id());
            if (notes != null) {
                credits.put(invoice.id(), new Credits(notes,
                        CreditNote.fullyCredited(invoice, lines.getOrDefault(invoice.id(), List.of()))));
            }
        }
        return credits;
    }

    @Transactional(readOnly = true)
    public Invoice get(UUID id) {
        return invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
    }

    /** The list, newest first, with every criterion of {@code filter} (Story 15.5: search, issue date range). */
    @Transactional(readOnly = true)
    public InvoiceRepository.Page list(InvoiceRepository.Filter filter, int page, int size) {
        return invoices.list(filter, page, size);
    }

    static DomainException notFound() {
        return new DomainException(ErrorCode.INVOICE_NOT_FOUND, "Invoice not found");
    }
}
