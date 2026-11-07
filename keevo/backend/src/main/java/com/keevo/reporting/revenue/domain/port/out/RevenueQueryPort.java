package com.keevo.reporting.revenue.domain.port.out;

/**
 * Story 20.9 (R4, FUS-FR24) — the single revenue source of the reporting module's revenue figures: the owner
 * dashboard, the daily and weekly store reports, the seller ranking and the profitability reports. Those consumers
 * never read {@code sales} / {@code invoices} for revenue nor repeat the status rules: each one puts the derived
 * tables of {@link Relations} in its {@code FROM} and filters / groups them as it used to filter {@code sales}.
 * (Revenue figures outside this module — client totals, sales-trend alerts, platform analytics — are routed to
 * later stories, see the 20.9 spec.)
 *
 * <p>Revenue = COMPLETED sales ∪ counted invoices (kind INVOICE, ISSUED or PAID, {@code source_sale_id IS NULL} — a
 * POS sale bridged to an automatic invoice counts once, as the sale —, neither deleted nor superseded) − credit notes
 * of counted invoices − manual credit notes ({@code source_event IS NULL}) of bridged invoices. Quotes, drafts and
 * cancelled documents never count. A sale counts at {@code occurred_at}, an invoice at its civil issue date (WAT
 * midnight, FUS-NFR9), a credit note at its own {@code issued_at}.
 *
 * <p>The relations are parameter-free SQL; PostgreSQL pushes the consumer's predicates into each {@code UNION ALL}
 * branch. Resolve them <b>once per figure</b> (one call, then reuse both strings).
 */
public interface RevenueQueryPort {

    /**
     * The revenue relations of the current tenant: sales always; invoices and credit notes when the invoicing tables
     * exist in the tenant schema (their data exists, whatever the module's current activation). Never provisions
     * anything, never fails for a missing module.
     */
    Relations current();

    /**
     * Sales-only relations, for store-scoped figures (a store filter, or a query joined on {@code stores}): documents
     * carry no store yet, so these figures never touch the invoicing tables.
     */
    Relations storeScoped();

    /**
     * The two derived tables (each parenthesised — alias it).
     *
     * @param documents one row per revenue document: {@code source} ({@code SALE} | {@code INVOICE} |
     *                  {@code CREDIT_NOTE}), {@code doc_id}, {@code store_id} (null for documents),
     *                  {@code employee_id} (seller: the sale's actor, the invoice's creator; a credit note's is its
     *                  invoice's — may be null for a document), {@code occurred_at}, {@code created_at},
     *                  {@code payment_mode} (sales only), {@code total_amount} (bigint, negative for a credit note),
     *                  {@code doc_count} (1 for a sale or an invoice, 0 for a credit note)
     * @param lines     one row per revenue line: {@code source}, {@code doc_id}, {@code store_id},
     *                  {@code employee_id}, {@code occurred_at}, {@code created_at}, {@code product_id} (null for an
     *                  invoice line without product — revenue, never a per-product margin), {@code product_name},
     *                  {@code quantity} (numeric, negative for a credit-note line), {@code subtotal} and
     *                  {@code price_amount} (bigint line amount; a document line's total TTC; negative for a credit
     *                  note)
     */
    record Relations(String documents, String lines) {
    }
}
