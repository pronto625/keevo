package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.event.catalog.invoicing.InvoicePaidEvent;
import com.keevo.shared.event.catalog.invoicing.InvoicePaymentRecordedEvent;
import com.keevo.shared.event.catalog.invoicing.InvoicePaymentRecordedEvent.Kind;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.4 — collections of an invoice (FUS-FR20): payments received (a deposit is the first one), scheduled
 * instalments, their settlement, and the settlement of the whole invoice (V1 {@code POST /pay}, {@code PAY_INVOICE}).
 *
 * <p>Pattern: <b>aggregate + pure policy</b>. Every mutation runs in one transaction holding the invoice row lock
 * ({@code FOR UPDATE}), so concurrent devices serialise; {@link PaymentStateCalculator} decides the state and the
 * overpayment refusal; the state is stored on the invoice ({@code payment_state}) with its {@code updated_at} moved
 * (the sync pull carries it) — becoming OVERDUE is left to the daily job, which notifies the owner; reaching PAID
 * on an ISSUED invoice moves the legal status to PAID with the latest payment's date and method
 * ({@link InvoicePaidEvent}). Idempotency rests on the device {@code paymentId} ({@code ON CONFLICT DO NOTHING} +
 * content comparison: same content → the invoice, no second row; different content → {@code VALIDATION_ERROR}).
 *
 * <p>Cost of a change: 1 locked read (+ lines), the invoice's rows (a handful), its credit notes, its stored state,
 * 1 insert / update, 1 invoice update, 1 re-read — constant.
 */
@Service
public class InvoicePaymentUseCase {

    /** French refusal of a payment id already used with another content. */
    static final String PAYMENT_ID_REUSED = "Ce paiement a déjà été enregistré avec un autre contenu";

    private final InvoiceRepository invoices;
    private final InvoicePaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public InvoicePaymentUseCase(InvoiceRepository invoices, InvoicePaymentRepository payments,
                                 CreditNoteRepository creditNotes, ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.events = events;
        this.clock = clock;
    }

    /**
     * A payment received ({@code paidOn}) or an instalment ({@code dueDate}) — exactly one of the two.
     *
     * @param paymentId the id chosen by the device ({@code null}: a new one)
     */
    public record NewPayment(UUID paymentId, long amount, LocalDate paidOn, LocalDate dueDate, String method,
                             String note) {
    }

    /** The invoice after the operation; {@code created} false for a replay or a no-op. */
    public record Result(Invoice invoice, boolean created) {
    }

    /**
     * Records a payment received (ISSUED only; date rules of {@link Invoice#paymentInstant}) or schedules an
     * instalment (DRAFT or ISSUED). Quote → 409 {@code INVOICE_INVALID_TRANSITION}; rows above the amount due → 409
     * {@code OVERPAYMENT}, nothing written; the same {@code paymentId} again → the invoice, no second row (different
     * content → {@code VALIDATION_ERROR}).
     */
    @Transactional
    public Result add(UUID invoiceId, NewPayment request, UUID actorId, String tenantId) {
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow(InvoiceQueryUseCase::notFound);
        invoice.assertInvoice();
        if (request.paymentId() != null) {
            var existing = payments.findById(request.paymentId());
            if (existing.isPresent()) {
                return replay(existing.get(), invoice, request);
            }
        }
        if ((request.paidOn() == null) == (request.dueDate() == null)) {
            throw invalid("Indiquez soit la date du paiement reçu, soit la date d'échéance (une seule des deux)");
        }
        Instant now = clock.instant();
        UUID id = request.paymentId() != null ? request.paymentId() : UUID.randomUUID();
        InvoicePayment row;
        if (request.paidOn() != null) {
            invoice.assertCollectable();
            row = InvoicePayment.received(id, invoiceId, request.amount(), invoice.paymentInstant(request.paidOn(), now),
                    request.method(), request.note());
        } else {
            invoice.assertSchedulable();
            PaymentStateCalculator.assertDueDateNotPast(request.dueDate(), LocalDate.ofInstant(now, KeevoZones.WAT));
            row = InvoicePayment.scheduled(id, invoiceId, request.amount(), request.dueDate(), request.method(),
                    request.note());
        }
        List<InvoicePayment> rows = new ArrayList<>(payments.findByInvoice(invoiceId));
        long due = amountDue(invoice);
        PaymentStateCalculator.assertWithinDue(due, rows, row.amount());
        if (!payments.insertIfAbsent(row, actorId, now)) {
            // the id was taken meanwhile (by another invoice: this one is locked)
            return replay(payments.findById(id).orElseThrow(() -> invalid(PAYMENT_ID_REUSED)), invoice, request);
        }
        rows.add(row);
        recompute(invoice, rows, due, now, actorId, tenantId);
        events.publishEvent(new InvoicePaymentRecordedEvent(invoiceId, invoice.number(), invoice.client().name(),
                row.isPaid() ? Kind.RECEIVED : Kind.SCHEDULED, row.amount(), row.dueDate(), row.paidAt(),
                row.method(), actorId, tenantId, now));
        return new Result(reread(invoiceId), true);
    }

    /**
     * Settles the instalment {@code paymentId} of an ISSUED invoice on {@code paidOn} (today by default); already paid
     * → the invoice unchanged ({@code created} false). Unknown on this invoice → 404 {@code INVOICE_NOT_FOUND}.
     */
    @Transactional
    public Result settle(UUID invoiceId, UUID paymentId, LocalDate paidOn, String method, UUID actorId,
                         String tenantId) {
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow(InvoiceQueryUseCase::notFound);
        invoice.assertInvoice();
        InvoicePayment row = payments.findById(paymentId).filter(p -> invoiceId.equals(p.invoiceId()))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.INVOICE_NOT_FOUND,
                        "Échéance introuvable sur cette facture"));
        if (row.isPaid()) {
            return new Result(invoice, false);
        }
        invoice.assertCollectable();
        Instant now = clock.instant();
        InvoicePayment settled = row.settled(invoice.paymentInstant(paidOn, now), method);
        long due = amountDue(invoice);
        assertInvariant(due, payments.findByInvoice(invoiceId)); // never records more than owed
        payments.settle(paymentId, settled.paidAt(), InvoicePayment.clean(method), now);
        List<InvoicePayment> rows = payments.findByInvoice(invoiceId);
        recompute(invoice, rows, due, now, actorId, tenantId);
        events.publishEvent(new InvoicePaymentRecordedEvent(invoiceId, invoice.number(), invoice.client().name(),
                Kind.SETTLED, settled.amount(), settled.dueDate(), settled.paidAt(), settled.method(), actorId,
                tenantId, now));
        return new Result(reread(invoiceId), true);
    }

    /**
     * The settlement of the whole invoice (V1 {@code POST /pay}, sync {@code PAY_INVOICE}, POS automatic invoices):
     * every unpaid instalment becomes paid on {@code paidOn} with {@code method}, a payment of the balance is added,
     * state and status PAID ({@link InvoicePaidEvent}). {@code invoice} is row-locked by the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice settleAll(Invoice invoice, String method, LocalDate paidOn, UUID actorId, String tenantId) {
        invoice.assertCollectable();
        Instant now = clock.instant();
        Instant paidAt = invoice.paymentInstant(paidOn, now);
        long due = amountDue(invoice);
        assertInvariant(due, payments.findByInvoice(invoice.id())); // never records more than owed
        payments.settleUnpaid(invoice.id(), paidAt, InvoicePayment.clean(method), now);
        List<InvoicePayment> rows = payments.findByInvoice(invoice.id());
        long balance = due - PaymentStateCalculator.committed(rows);
        if (balance > 0) {
            payments.insertIfAbsent(InvoicePayment.received(UUID.randomUUID(), invoice.id(), balance, paidAt, method,
                    null), actorId, now);
        }
        Invoice paid = invoice.markedPaid(paidAt, method, now);
        invoices.markPaid(paid);
        payments.storeState(invoice.id(), PaymentState.PAID, now);
        publishPaid(paid, actorId, tenantId, now);
        return paid;
    }

    /** Whether a payment was received on the invoice (an ISSUED invoice with one is no longer cancellable). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasPayments(UUID invoiceId) {
        return payments.anyPaid(invoiceId);
    }

    /** French refusal of a credit note that would leave more received than owed (Story 20.4). */
    static final String CREDIT_BELOW_PAID = "Le montant déjà encaissé dépasse le reste dû après cet avoir.";

    /**
     * Before a credit note of {@code creditTtc} on the ISSUED {@code invoice} (row-locked by the caller, nothing written
     * yet): more already received than the new amount due → 409 {@code INVOICE_INVALID_TRANSITION} « Le montant déjà
     * encaissé dépasse le reste dû après cet avoir. »; otherwise the unpaid instalments are reduced or deleted, latest
     * due date first, until every row fits the new amount due. A PAID invoice (its credit note is a refund) and a draft
     * are left as they are.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void beforeCredit(Invoice invoice, long creditTtc) {
        if (invoice.isQuote() || invoice.status() != InvoiceStatus.ISSUED) {
            return;
        }
        List<InvoicePayment> rows = payments.findByInvoice(invoice.id());
        if (rows.isEmpty()) {
            return;
        }
        long newDue = Math.max(0, amountDue(invoice) - creditTtc);
        if (PaymentStateCalculator.amountPaid(rows) > newDue) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION, CREDIT_BELOW_PAID,
                    Map.of("status", "déjà encaissée au-delà de l'avoir"));
        }
        Instant now = clock.instant();
        long excess = PaymentStateCalculator.committed(rows) - newDue;
        List<InvoicePayment> unpaid = rows.stream().filter(p -> !p.isPaid())
                .sorted(Comparator.comparing(InvoicePayment::dueDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        for (InvoicePayment instalment : unpaid) {
            if (excess <= 0) {
                break;
            }
            long cut = Math.min(excess, instalment.amount());
            if (cut == instalment.amount()) {
                payments.delete(instalment.id());
            } else {
                payments.updateAmount(instalment.id(), instalment.amount() - cut, now);
            }
            excess -= cut;
        }
    }

    /**
     * After a credit note on {@code invoice} (row-locked by the caller, {@link #beforeCredit} applied): the amount due
     * dropped, the state is recomputed and stored (amount due 0 → PAID); the legal status is never changed by a credit
     * note.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshAfterCredit(Invoice invoice, UUID actorId, String tenantId) {
        if (!PaymentStateCalculator.applies(invoice)) {
            return;
        }
        Instant now = clock.instant();
        List<InvoicePayment> rows = payments.findByInvoice(invoice.id());
        PaymentState state = rows.isEmpty() && invoice.status() == InvoiceStatus.PAID ? PaymentState.PAID
                : PaymentStateCalculator.afterChange(amountDue(invoice), rows,
                        LocalDate.ofInstant(now, KeevoZones.WAT), payments.storedState(invoice.id()),
                        invoice.terms().dueDate());
        payments.storeState(invoice.id(), state, now);
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────────────────────

    /** A creation replayed with an id already stored: same content → the invoice; otherwise refused. */
    private Result replay(InvoicePayment existing, Invoice invoice, NewPayment request) {
        if (!existing.sameCreation(invoice.id(), request.amount(), request.paidOn(), request.dueDate(),
                request.method(), request.note())) {
            throw invalid(PAYMENT_ID_REUSED);
        }
        return new Result(invoice, false);
    }

    /**
     * Stores the state of {@code invoice} with {@code rows}; on reaching PAID while ISSUED (and a payment received),
     * moves the legal status to PAID with the latest payment. A draft (instalments scheduled before the issue) only has
     * its {@code updated_at} moved.
     */
    private void recompute(Invoice invoice, List<InvoicePayment> rows, long due, Instant now, UUID actorId,
                           String tenantId) {
        if (!PaymentStateCalculator.applies(invoice)) {
            payments.touch(invoice.id(), now);
            return;
        }
        // becoming overdue is the daily job's transition (with its owner notification), never a payment's
        PaymentState state = PaymentStateCalculator.afterChange(due, rows, LocalDate.ofInstant(now, KeevoZones.WAT),
                payments.storedState(invoice.id()), invoice.terms().dueDate());
        if (state == PaymentState.PAID && invoice.status() == InvoiceStatus.ISSUED) {
            var latest = rows.stream().filter(InvoicePayment::isPaid)
                    .max(Comparator.comparing(InvoicePayment::paidAt));
            if (latest.isPresent()) {
                Invoice paid = invoice.markedPaid(latest.get().paidAt(), latest.get().method(), now);
                invoices.markPaid(paid);
                publishPaid(paid, actorId, tenantId, now);
            }
        }
        payments.storeState(invoice.id(), state, now);
    }

    /** The invariant « every row fits the amount due » ({@code OVERPAYMENT} when broken), checked before settling. */
    private static void assertInvariant(long due, List<InvoicePayment> rows) {
        if (PaymentStateCalculator.committed(rows) > due) {
            PaymentStateCalculator.assertWithinDue(due, List.of(), PaymentStateCalculator.committed(rows));
        }
    }

    private void publishPaid(Invoice paid, UUID actorId, String tenantId, Instant now) {
        events.publishEvent(new InvoicePaidEvent(paid.id(), paid.number(), paid.client().name(),
                paid.totals().totalTtc(), paid.paymentMethod(), paid.paidAt(), actorId, tenantId, now));
    }

    /** {@code totalTtc − creditedTtc} of the invoice. */
    private long amountDue(Invoice invoice) {
        long credited = creditNotes.summaries(List.of(invoice.id())).getOrDefault(invoice.id(), List.of()).stream()
                .mapToLong(CreditNoteRepository.Summary::totalTtc).sum();
        return PaymentStateCalculator.amountDue(invoice.totals().totalTtc(), credited);
    }

    private Invoice reread(UUID invoiceId) {
        return invoices.findById(invoiceId).orElseThrow(InvoiceQueryUseCase::notFound);
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid invoice payment", Map.of("reason", reason));
    }
}
