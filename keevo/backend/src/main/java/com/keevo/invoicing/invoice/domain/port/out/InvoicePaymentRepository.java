package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.PaymentState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.4 — the collection rows of the current tenant's invoices ({@code invoice_payments}) and the stored
 * {@code invoices.payment_state}. Every write runs in the caller's transaction, the invoice row being locked by it.
 */
public interface InvoicePaymentRepository {

    /** The rows of one invoice, oldest first (read under the caller's invoice lock). */
    List<InvoicePayment> findByInvoice(UUID invoiceId);

    /** The row {@code id}, whatever its invoice (idempotency check of a replayed creation). */
    Optional<InvoicePayment> findById(UUID id);

    /**
     * Inserts the row unless its id is already used (by any invoice): false, nothing written, the caller's
     * transaction intact.
     */
    boolean insertIfAbsent(InvoicePayment payment, UUID createdBy, Instant now);

    /** Whether at least one payment was received on the invoice (a row with a payment date). */
    boolean anyPaid(UUID invoiceId);

    /**
     * Marks every unpaid instalment of the invoice paid at {@code paidAt}; {@code method} replaces theirs when given.
     * One set-based UPDATE; returns the number of rows settled.
     */
    int settleUnpaid(UUID invoiceId, Instant paidAt, String method, Instant now);

    /** Marks the unpaid row {@code id} paid at {@code paidAt} with {@code method} (status-guarded). */
    void settle(UUID id, Instant paidAt, String method, Instant now);

    /**
     * Stores the invoice's {@code payment_state} and moves its {@code updated_at} (never backwards) so the sync pull
     * carries the change.
     */
    void storeState(UUID invoiceId, PaymentState state, Instant now);

    /** Story 20.4 — an unpaid instalment reduced to {@code amount} (> 0) by a credit note. */
    void updateAmount(UUID id, long amount, Instant now);

    /** Story 20.4 — an unpaid instalment removed by a credit note. */
    void delete(UUID id);

    /** The invoice's stored {@code payment_state} ({@code null}: never computed). */
    PaymentState storedState(UUID invoiceId);

    /** Moves the invoice's {@code updated_at} only (a payment change that leaves the state as it was). */
    void touch(UUID invoiceId, Instant now);

    /** The stored state and the rows of each invoice — one query for a page (invoices without row are present). */
    Map<UUID, Ledger> ledgers(Collection<UUID> invoiceIds);

    /** What is stored for one invoice: its {@code payment_state} ({@code null}: never computed) and its rows. */
    record Ledger(PaymentState storedState, List<InvoicePayment> rows) {

        public static final Ledger EMPTY = new Ledger(null, List.of());

        public Ledger {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * The daily overdue job, in the bound tenant: one set-based UPDATE moving every ISSUED invoice with an unpaid
     * instalment due before {@code today} to {@code OVERDUE} when its stored state differs ({@code updated_at} moved);
     * returns their numbers.
     */
    List<String> markOverdue(LocalDate today, Instant now);

    /** The tenant schemas holding {@code invoice_payments} (catalogue query; the others have nothing to mark). */
    java.util.Set<String> schemasWithPayments();
}
