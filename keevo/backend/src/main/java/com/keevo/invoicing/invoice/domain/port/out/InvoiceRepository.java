package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;

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

    /** ISSUED → CANCELLED: status, {@code cancelled_at}, reason, {@code updated_at} only. */
    void markCancelled(Invoice cancelled);

    /**
     * Story 14.2 — records one WhatsApp sending already delivered: {@code last_sent_at}, {@code last_sent_to},
     * {@code send_count + 1} only, whatever the current status (checked before the delivery).
     */
    void markSent(Invoice sent);

    /**
     * Deletes a DRAFT; returns false when no draft has this id. Story 15.5b: a soft delete ({@code deleted_at},
     * {@code updated_at} moved) so the sync pull sends a tombstone to the other devices.
     */
    boolean deleteDraft(UUID id, java.time.Instant at);

    /** Newest first, every criterion of {@code filter} applied. */
    Page list(Filter filter, int page, int size);

    /**
     * Criteria of the invoice list, each one optional ({@code null} = not filtered): {@code status}; {@code saleId} =
     * only the active invoice created from that sale (Story 14.5 — at most one); Story 15.5: {@code search} = part of the number or of the client name,
     * case-insensitive, already trimmed; {@code from} / {@code to} = issue date range, inclusive (a draft has no issue
     * date: excluded as soon as one bound is given).
     */
    record Filter(InvoiceStatus status, UUID saleId, String search, java.time.LocalDate from,
                  java.time.LocalDate to) {
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

    /** One row of the delta: the invoice, or a deleted draft ({@code deleted}: only its id and times matter). */
    record DeltaItem(Invoice invoice, boolean deleted) {
    }
}
