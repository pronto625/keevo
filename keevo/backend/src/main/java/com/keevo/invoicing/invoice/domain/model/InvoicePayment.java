package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.time.KeevoZones;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 20.4 — one row of an invoice's collection ({@code invoice_payments}): a payment received ({@code paidAt} set)
 * or a scheduled instalment ({@code dueDate} set, {@code paidAt} {@code null} until it is settled — a settled
 * instalment keeps its due date). Amount in XAF francs, always {@code > 0}. {@code legacy}: the single payment read
 * from an invoice paid before Story 20.4 (no row stored, {@code id} {@code null}).
 */
public record InvoicePayment(
        UUID id,
        UUID invoiceId,
        long amount,
        LocalDate dueDate,
        Instant paidAt,
        String method,
        String note,
        boolean legacy) {

    public InvoicePayment {
        method = InvoicingSettings.trimToNull(method);
        note = InvoicingSettings.trimToNull(note);
    }

    /** Trimmed text, {@code null} when blank (method, note). */
    public static String clean(String text) {
        return InvoicingSettings.trimToNull(text);
    }

    /** A payment received at {@code paidAt}. */
    public static InvoicePayment received(UUID id, UUID invoiceId, long amount, Instant paidAt, String method,
                                          String note) {
        return new InvoicePayment(id, invoiceId, amount, null, paidAt, method, note, false);
    }

    /** An instalment due on {@code dueDate}, not paid yet. */
    public static InvoicePayment scheduled(UUID id, UUID invoiceId, long amount, LocalDate dueDate, String method,
                                           String note) {
        return new InvoicePayment(id, invoiceId, amount, dueDate, null, method, note, false);
    }

    public boolean isPaid() {
        return paidAt != null;
    }

    /** An unpaid instalment whose due date is before {@code today}. */
    public boolean isOverdue(LocalDate today) {
        return paidAt == null && dueDate != null && dueDate.isBefore(today);
    }

    /** The instalment settled at {@code at}; its method replaced when {@code newMethod} is given. */
    public InvoicePayment settled(Instant at, String newMethod) {
        String m = InvoicingSettings.trimToNull(newMethod);
        return new InvoicePayment(id, invoiceId, amount, dueDate, at, m == null ? method : m, note, legacy);
    }

    /**
     * Whether a replayed creation with the same id asks for this same row: same invoice, amount,
     * note, and — for an instalment — the same due date; for a payment received — the same day (Africa/Douala) and
     * method. A settled instalment still matches its original creation.
     */
    public boolean sameCreation(UUID requestInvoiceId, long requestAmount, LocalDate requestPaidOn,
                                LocalDate requestDueDate, String requestMethod, String requestNote) {
        if (!Objects.equals(invoiceId, requestInvoiceId) || amount != requestAmount
                || !Objects.equals(note, InvoicingSettings.trimToNull(requestNote))) {
            return false;
        }
        if (requestDueDate != null) {
            return requestPaidOn == null && Objects.equals(dueDate, requestDueDate);
        }
        return dueDate == null && paidAt != null && requestPaidOn != null && day(paidAt).equals(requestPaidOn)
                && Objects.equals(method, InvoicingSettings.trimToNull(requestMethod));
    }

    private static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, KeevoZones.WAT);
    }
}
