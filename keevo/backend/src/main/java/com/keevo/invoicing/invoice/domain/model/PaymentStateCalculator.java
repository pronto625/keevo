package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.XafAmounts;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Story 20.4 — the single source of an invoice's collection figures (pure policy, no I/O). Used by the use cases under
 * the invoice row lock, by the reads (REST, sync pull) and by the published calculation cases
 * ({@code keevo/contracts/invoice-payment-cases.json}).
 *
 * <ul>
 *   <li><b>amount due</b> = {@code totalTtc − creditedTtc} (a credit note reduces what the client owes, never below 0);</li>
 *   <li><b>amount paid</b> = the sum of the rows with a payment date;</li>
 *   <li><b>state</b>, in this order: {@code PAID} when paid ≥ due; {@code OVERDUE} when an unpaid instalment is due
 *       before today (Africa/Douala); {@code PARTIAL} when something is paid; {@code UNPAID} otherwise;</li>
 *   <li><b>invariant</b>: the sum of every row (paid and scheduled) never exceeds the amount due
 *       ({@code OVERPAYMENT}).</li>
 * </ul>
 * Complexity: one pass over the invoice's rows (a handful).
 */
public final class PaymentStateCalculator {

    private PaymentStateCalculator() {
    }

    /**
     * What a read shows: {@code state} ({@code null} for a draft, a cancelled invoice or a quote), amounts, and the rows
     * — for an invoice paid before Story 20.4 (status PAID, no row), one {@code legacy} payment of the amount due.
     */
    public record Collection(PaymentState state, long amountPaid, long amountDue, List<InvoicePayment> payments) {

        public Collection {
            payments = payments == null ? List.of() : List.copyOf(payments);
        }

        public String stateLabel() {
            return state == null ? null : state.label();
        }
    }

    /** {@code totalTtc − creditedTtc}, never negative. */
    public static long amountDue(long totalTtc, long creditedTtc) {
        return Math.max(0, totalTtc - creditedTtc);
    }

    /** Sum of the rows with a payment date. */
    public static long amountPaid(List<InvoicePayment> rows) {
        long sum = 0;
        for (InvoicePayment p : rows) {
            if (p.isPaid()) {
                sum = Math.addExact(sum, p.amount());
            }
        }
        return sum;
    }

    /** Sum of every row, paid or scheduled. */
    public static long committed(List<InvoicePayment> rows) {
        long sum = 0;
        for (InvoicePayment p : rows) {
            sum = Math.addExact(sum, p.amount());
        }
        return sum;
    }

    /** The state of an issued invoice owing {@code amountDue}, with {@code rows}, on {@code today}. */
    public static PaymentState state(long amountDue, List<InvoicePayment> rows, LocalDate today) {
        return state(amountDue, rows, today, null);
    }

    /**
     * Story 20.5 — same, with the invoice's own due date ({@code null} = none): an invoice not fully paid whose due date
     * is before today is late too.
     */
    public static PaymentState state(long amountDue, List<InvoicePayment> rows, LocalDate today,
                                     LocalDate invoiceDueDate) {
        long paid = amountPaid(rows);
        if (paid >= amountDue) {
            return PaymentState.PAID;
        }
        if (invoiceDueDate != null && invoiceDueDate.isBefore(today)) {
            return PaymentState.OVERDUE;
        }
        for (InvoicePayment p : rows) {
            if (p.isOverdue(today)) {
                return PaymentState.OVERDUE;
            }
        }
        return paid > 0 ? PaymentState.PARTIAL : PaymentState.UNPAID;
    }

    /**
     * The state stored after a payment change, {@code stored} being the state stored before it. Becoming
     * {@code OVERDUE} is the daily job's transition (it notifies the owner, once): a payment change keeps an invoice
     * already overdue {@code OVERDUE} while an instalment is still late, clears it when none is, and never makes it
     * overdue itself ({@code PARTIAL} / {@code UNPAID} until the job's run).
     */
    public static PaymentState afterChange(long amountDue, List<InvoicePayment> rows, LocalDate today,
                                           PaymentState stored) {
        return afterChange(amountDue, rows, today, stored, null);
    }

    /** Story 20.5 — same, with the invoice's own due date ({@code null} = none). */
    public static PaymentState afterChange(long amountDue, List<InvoicePayment> rows, LocalDate today,
                                           PaymentState stored, LocalDate invoiceDueDate) {
        PaymentState computed = state(amountDue, rows, today, invoiceDueDate);
        if (computed == PaymentState.OVERDUE && stored != PaymentState.OVERDUE) {
            return amountPaid(rows) > 0 ? PaymentState.PARTIAL : PaymentState.UNPAID;
        }
        return computed;
    }

    /**
     * Whether the daily overdue job marks an active ISSUED invoice {@code OVERDUE} — the rule of its set-based UPDATE
     * ({@code JdbcInvoicePaymentRepository.markOverdue}): an unpaid row past due, and a stored state neither
     * {@code OVERDUE} (already marked: no second notification) nor {@code PAID}.
     */
    public static boolean markedByJob(PaymentState stored, List<InvoicePayment> rows, LocalDate today) {
        if (stored == PaymentState.OVERDUE || stored == PaymentState.PAID) {
            return false;
        }
        return rows.stream().anyMatch(p -> p.isOverdue(today));
    }

    /** An instalment is never scheduled in the past ({@code VALIDATION_ERROR}, French). */
    public static void assertDueDateNotPast(LocalDate dueDate, LocalDate today) {
        if (dueDate.isBefore(today)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Due date in the past",
                    Map.of("reason", "La date d'échéance ne peut pas être passée"));
        }
    }

    /**
     * Refuses a new row of {@code amount} when the rows would then exceed {@code amountDue} → 409 {@code OVERPAYMENT}
     * « Le montant dépasse ce qui reste dû sur la facture (reste dû : X FCFA) », X = due − existing rows.
     */
    public static void assertWithinDue(long amountDue, List<InvoicePayment> existing, long amount) {
        if (amount <= 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Payment amount must be positive",
                    Map.of("reason", "Le montant doit être supérieur à 0"));
        }
        long remaining = amountDue - committed(existing);
        if (amount > remaining) {
            String rest = XafAmounts.group(Math.max(0, remaining));
            throw DomainException.userFacing(ErrorCode.OVERPAYMENT,
                    "Le montant dépasse ce qui reste dû sur la facture (reste dû : " + rest + " FCFA)",
                    Map.of("remaining", rest));
        }
    }

    /** Whether the state of {@code invoice} is computed at all: an invoice (not a quote) ISSUED or PAID. */
    public static boolean applies(Invoice invoice) {
        return !invoice.isQuote()
                && (invoice.status() == InvoiceStatus.ISSUED || invoice.status() == InvoiceStatus.PAID);
    }

    /**
     * The collection of {@code invoice} as read: the stored state when there is one; otherwise derived — no row: PAID
     * status → PAID with one legacy payment, ISSUED → UNPAID; rows: {@link #afterChange} (overdue only once the job
     * marked it).
     */
    public static Collection view(Invoice invoice, long creditedTtc, PaymentState stored, List<InvoicePayment> rows,
                                  LocalDate today) {
        List<InvoicePayment> payments = rows == null ? List.of() : rows;
        long due = amountDue(invoice.totals().totalTtc(), creditedTtc);
        if (!applies(invoice)) {
            return new Collection(null, amountPaid(payments), due, payments);
        }
        if (payments.isEmpty() && invoice.status() == InvoiceStatus.PAID) {
            InvoicePayment legacy = new InvoicePayment(null, invoice.id(), due, null, invoice.paidAt(),
                    invoice.paymentMethod(), null, true);
            return new Collection(PaymentState.PAID, due, due, due > 0 ? List.of(legacy) : List.of());
        }
        PaymentState state = stored;
        if (state == null) {
            state = payments.isEmpty() ? PaymentState.UNPAID
                    : afterChange(due, payments, today, null, invoice.terms().dueDate());
        }
        return new Collection(state, amountPaid(payments), due, payments);
    }
}
