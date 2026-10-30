package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invoices of the current tenant (Story 14.1), in the module-owned tables of its schema. */
public interface InvoiceRepository {

    Optional<Invoice> findById(UUID id);

    /** Same, row-locked until the end of the caller's transaction (serialises concurrent transitions). */
    Optional<Invoice> findByIdForUpdate(UUID id);

    /**
     * Inserts the invoice with its lines. Story 14.4: an invoice whose {@code sourceSaleId} is already used fails with
     * a duplicate-key failure (unique index — one invoice per sale).
     */
    void insert(Invoice invoice);

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

    /** Deletes a DRAFT (and its lines); returns false when no draft has this id. */
    boolean deleteDraft(UUID id);

    /**
     * Newest first; {@code status} null = every status; {@code saleId} non-null = only the invoice created from that
     * sale (Story 14.5 — at most one, {@code source_sale_id} is unique).
     */
    Page list(InvoiceStatus status, UUID saleId, int page, int size);

    /**
     * Next number of the year's gapless sequence, incremented in the caller's transaction (a rollback gives the
     * number back): 1, 2, 3…
     */
    long nextNumber(int year);

    record Page(List<Invoice> items, long totalElements) {
    }
}
