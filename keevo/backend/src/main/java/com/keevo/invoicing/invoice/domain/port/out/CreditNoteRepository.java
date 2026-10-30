package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.CreditNoteLine;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Credit notes (« avoirs ») of the current tenant (Story 14.7), in the module-owned tables of its schema. */
public interface CreditNoteRepository {

    /**
     * Inserts the credit note with its lines. A {@code sourceEvent} already used fails with a duplicate-key failure
     * (unique index — one automatic credit note per sale event).
     */
    void insert(CreditNote creditNote);

    /** The credit note with its lines and the number of the invoice it credits. */
    Optional<CreditNote> findById(UUID id);

    /** Every line of every credit note of the invoice (what is already credited). */
    List<CreditNoteLine> linesOfInvoice(UUID invoiceId);

    /** Whether the invoice has at least one credit note. */
    boolean existsForInvoice(UUID invoiceId);

    /** Whether an automatic credit note was already issued for the sale event {@code sourceEvent}. */
    boolean existsBySourceEvent(String sourceEvent);

    /** The credit notes of each invoice, oldest first (invoices without credit note are absent from the map). */
    Map<UUID, List<Summary>> summaries(Collection<UUID> invoiceIds);

    /**
     * Next number of the year's gapless credit-note sequence, incremented in the caller's transaction (a rollback
     * gives the number back): 1, 2, 3… — independent of the invoice sequence.
     */
    long nextNumber(int year);

    /** A credit note as listed on its invoice. */
    record Summary(UUID id, String number, Instant issuedAt, long totalTtc, String reason) {
    }
}
