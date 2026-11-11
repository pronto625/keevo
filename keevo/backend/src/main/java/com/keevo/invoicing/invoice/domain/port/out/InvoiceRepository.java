package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.PaymentState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invoices of the current tenant (Story 14.1), in the module-owned tables of its schema. */
public interface InvoiceRepository {

    /** Story 15.5b — a deleted draft is absent. */
    Optional<Invoice> findById(UUID id);

    /** Story 15.5b — the invoice {@code id} even when it is a deleted draft (creation idempotency). */
    Optional<Invoice> findAnyById(UUID id);

    /** Same, row-locked until the end of the caller's transaction (serialises concurrent transitions). */
    Optional<Invoice> findByIdForUpdate(UUID id);

    /**
     * Inserts the invoice with its lines. Story 14.4: an invoice whose {@code sourceSaleId} is already used fails with
     * a duplicate-key failure (unique index — one invoice per sale).
     */
    void insert(Invoice invoice);

    /**
     * Story 15.5b — inserts the invoice unless one (deleted or not) already has its id: false, nothing written. Safe
     * against a concurrent insert of the same id (it waits for it, then answers false).
     */
    boolean insertIfAbsent(Invoice invoice);

    /**
     * Story 20.3 — inserts the invoice of a quote conversion unless its id is taken or an invoice already comes from
     * the same quote (unique {@code source_quote_id}): false, nothing written, the caller's transaction intact.
     */
    boolean insertConversion(Invoice invoice);

    /** Story 20.3 — the invoice created from the quote {@code quoteId}, if any. */
    Optional<Invoice> findBySourceQuote(UUID quoteId);

    /** Story 14.4 — whether an invoice was already created from the sale {@code saleId} (POS → Invoicing bridge). */
    boolean existsBySourceSale(UUID saleId);

    /**
     * Story 14.7 — the active (not superseded) invoice created from the sale {@code saleId}, row-locked until the end
     * of the caller's transaction; empty when the sale was never invoiced automatically.
     */
    Optional<Invoice> findActiveBySourceSaleForUpdate(UUID saleId);

    /**
     * Story 14.7 — marks the automatic invoice {@code id} as replaced by a new invoice of its corrected sale
     * ({@code superseded_at} only, once): the only write on an issued invoice besides its lifecycle transitions.
     */
    void markSuperseded(UUID id, java.time.Instant at);

    /** A DRAFT edit: client, VAT parameters, totals, {@code updated_at}, and its lines replaced. */
    void updateDraft(Invoice draft);

    /**
     * DRAFT → ISSUED: number, issue date, seller snapshot, frozen VAT parameters / totals and lines,
     * {@code issued_at}, {@code updated_at}. The only write that touches an invoice's lines after its drafting.
     */
    void issue(Invoice issued);

    /** ISSUED → PAID: status, {@code paid_at}, payment method, {@code updated_at} only. */
    void markPaid(Invoice paid);

    /**
     * ISSUED → CANCELLED: status, {@code cancelled_at}, reason, {@code updated_at} only. Story 20.3: also a DRAFT
     * quote → CANCELLED (no number).
     */
    void markCancelled(Invoice cancelled);

    /**
     * Story 20.3 — DRAFT quote → CONVERTED: status, {@code converted_invoice_id}, {@code converted_at},
     * {@code updated_at} only (status-guarded, the row is locked by the caller).
     */
    void markConverted(Invoice convertedQuote);

    /**
     * Story 14.2 — records one sending already delivered: {@code last_sent_at}, {@code last_sent_to},
     * {@code send_count + 1} only, whatever the current status (checked before the delivery). Story 20.8: and
     * {@code last_sent_channel} (WhatsApp, SMS or email).
     */
    void markSent(Invoice sent);

    /**
     * Story 20.8 — the outcome of the last queued request ({@code last_send_status}, its channel and error code), with
     * {@code updated_at} moved forward so the sync pull carries it. Never touches the legal content nor the sending
     * count.
     */
    void markSendStatus(java.util.UUID id, com.keevo.invoicing.invoice.domain.model.SendStatus status,
                        com.keevo.invoicing.invoice.domain.model.SendChannel channel, String errorCode,
                        java.time.Instant now);

    /**
     * Deletes a DRAFT; returns false when no draft has this id. Story 15.5b: a soft delete ({@code deleted_at},
     * {@code updated_at} moved) so the sync pull sends a tombstone to the other devices.
     */
    boolean deleteDraft(UUID id, java.time.Instant at);

    /** Newest first, every criterion of {@code filter} applied. */
    Page list(Filter filter, int page, int size);

    /**
     * Story 21.8 — the unpaid call list: every active issued invoice not yet paid (stored or legacy collection state
     * UNPAID, PARTIAL or OVERDUE) within {@code scope} (20.14) and the owner's {@code storeId} filter, grouped by client
     * (digits of the phone, else the trimmed lower-cased name), what each client still owes — total TTC minus credit
     * notes minus received payments, as {@code PaymentStateCalculator} computes it — largest first, at most
     * {@code limit} clients. One set-based statement.
     */
    OutstandingByClient outstandingByClient(com.keevo.invoicing.invoice.domain.model.DocumentScope scope, UUID storeId,
                                            int limit);

    /** The call list and its totals over every client owing something (not only the {@code clients} returned). */
    record OutstandingByClient(long clientCount, long invoiceCount, long totalDue, List<ClientOutstanding> clients) {

        public OutstandingByClient {
            clients = clients == null ? List.of() : List.copyOf(clients);
        }
    }

    /**
     * @param oldestDate the earliest due date of the client's unpaid invoices (issue date when none has a due date)
     */
    record ClientOutstanding(String name, String phone, int invoices, int overdueInvoices, long due,
                             java.time.LocalDate oldestDate) {
    }

    /**
     * Criteria of the invoice list, each one optional ({@code null} = not filtered): {@code status}; {@code saleId} =
     * only the active invoice created from that sale (Story 14.5 — at most one); Story 15.5: {@code search} = part of the number or of the client name,
     * case-insensitive, already trimmed; {@code from} / {@code to} = issue date range, inclusive (a draft has no issue
     * date: excluded as soon as one bound is given). Story 20.3: {@code kind} = invoices or quotes only ({@code null}
     * = both); a quote's date for {@code from} / {@code to} is its creation day (Africa/Douala) and {@code search} also
     * matches its reference {@code DV-XXXXXXXX}. Story 20.4: {@code paymentState} = issued or paid invoices in that
     * collection state (an invoice without stored state: PAID status = PAID, ISSUED = UNPAID).
     */
    /**
     * Story 20.14 — {@code scope}: the documents the actor sees ({@code null} = all), {@code storeId}: the owner's
     * explicit store filter ({@code null} = none). Story 21.8 — {@code paymentStates}: issued or paid invoices in any
     * of these collection states ({@code null} or empty = not filtered; combined with {@code paymentState} when both
     * are given), e.g. UNPAID + PARTIAL + OVERDUE for the AI unpaid tools.
     */
    record Filter(InvoiceStatus status, UUID saleId, String search, java.time.LocalDate from,
                  java.time.LocalDate to, InvoiceKind kind, PaymentState paymentState,
                  com.keevo.invoicing.invoice.domain.model.DocumentScope scope, UUID storeId,
                  java.util.Set<PaymentState> paymentStates) {

        public Filter {
            paymentStates = paymentStates == null ? java.util.Set.of() : java.util.Set.copyOf(paymentStates);
        }

        /** Before Story 21.8: one collection state at most. */
        public Filter(InvoiceStatus status, UUID saleId, String search, java.time.LocalDate from,
                      java.time.LocalDate to, InvoiceKind kind, PaymentState paymentState,
                      com.keevo.invoicing.invoice.domain.model.DocumentScope scope, UUID storeId) {
            this(status, saleId, search, from, to, kind, paymentState, scope, storeId, null);
        }

        /** Before Story 20.14: every document, no store filter. */
        public Filter(InvoiceStatus status, UUID saleId, String search, java.time.LocalDate from,
                      java.time.LocalDate to, InvoiceKind kind, PaymentState paymentState) {
            this(status, saleId, search, from, to, kind, paymentState, null, null);
        }

        /** Before Story 20.4: no collection-state criterion. */
        public Filter(InvoiceStatus status, UUID saleId, String search, java.time.LocalDate from,
                      java.time.LocalDate to, InvoiceKind kind) {
            this(status, saleId, search, from, to, kind, null);
        }

        /** Before Story 20.3: invoices only (the lists V1 apps know never show a quote). */
        public Filter(InvoiceStatus status, UUID saleId, String search, java.time.LocalDate from,
                      java.time.LocalDate to) {
            this(status, saleId, search, from, to, InvoiceKind.INVOICE);
        }
    }

    /**
     * Next number of the year's gapless sequence, incremented in the caller's transaction (a rollback gives the
     * number back): 1, 2, 3…
     */
    long nextNumber(int year);

    record Page(List<Invoice> items, long totalElements) {
    }

    /**
     * Story 15.5b — keyset cursor column of the sync pull of invoices ({@code idx_invoices_sync_keyset}, migration V6
     * and {@code TenantSchemaProvisioner.SYNC_KEYSET_INDEXES}). Every write the device must see moves it: lifecycle
     * transitions, draft edits, WhatsApp sendings, replacement by a corrected sale, credit notes.
     */
    String SYNC_CURSOR = "updated_at";

    /** Story 15.5b — whether the invoicing tables exist in the tenant schema (never provisioned: no delta). */
    boolean tablesExist();

    /**
     * Story 15.5b — the sync pull delta: invoices whose {@link #SYNC_CURSOR} is {@code >= since}, in
     * ({@code updated_at}, id as text) order, strictly after ({@code afterCursor}, {@code afterId}) when given, at most
     * {@code limit}, with their lines.
     */
    List<DeltaItem> delta(java.time.Instant since, java.time.Instant afterCursor, String afterId, int limit);

    /**
     * Story 20.14 — the same delta restricted to {@code scope} (the documents the puller sees; served by the V16
     * indexes {@code (created_by | store_id, updated_at, id)}).
     */
    List<DeltaItem> delta(java.time.Instant since, java.time.Instant afterCursor, String afterId, int limit,
                          com.keevo.invoicing.invoice.domain.model.DocumentScope scope);

    /** Story 20.14 — the creator and store of a document (deleted drafts included), by primary key. */
    java.util.Optional<com.keevo.invoicing.invoice.domain.model.DocumentScope.Owner> ownerOf(UUID invoiceId);

    /**
     * Story 19.5b — whether the document is a quote ({@code true}) or an invoice ({@code false}), by primary key;
     * empty when unknown.
     */
    java.util.Optional<Boolean> isQuote(UUID invoiceId);

    /** Story 20.14 — the creator and store of the invoice of a credit note. */
    java.util.Optional<com.keevo.invoicing.invoice.domain.model.DocumentScope.Owner> ownerOfCreditNote(
            UUID creditNoteId);

    /** One row of the delta: the invoice, or a deleted draft ({@code deleted}: only its id and times matter). */
    record DeltaItem(Invoice invoice, boolean deleted) {
    }
}
