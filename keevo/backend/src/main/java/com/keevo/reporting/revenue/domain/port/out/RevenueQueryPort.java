package com.keevo.reporting.revenue.domain.port.out;

/**
 * Story 20.9 (R4, FUS-FR24) — the single revenue source of every reporting figure (owner dashboard, daily and weekly
 * reports, profitability, seller ranking, exports). No consumer reads {@code sales} / {@code invoices} for revenue
 * nor repeats the status rules: each one puts one of these derived tables in its {@code FROM} and filters / groups
 * it as it used to filter {@code sales}.
 *
 * <p>Revenue = COMPLETED sales ∪ counted invoices (kind INVOICE, ISSUED or PAID, {@code source_sale_id IS NULL} — a
 * POS sale bridged to an automatic invoice counts once, as the sale —, neither deleted nor superseded) − credit notes
 * of those invoices. Quotes, drafts and cancelled documents never count. A sale counts at {@code occurred_at}, an
 * invoice at its civil issue date (WAT midnight, FUS-NFR9), a credit note at its own {@code issued_at}.
 *
 * <p>The relations are parameter-free SQL (usable from {@code JdbcTemplate} and native JPA queries alike); PostgreSQL
 * pushes the consumer's predicates into each {@code UNION ALL} branch. Which branches exist is decided per call from
 * the tenant's modules (module autonomy: {@code pos} disabled → no sales; {@code invoicing} disabled → no documents).
 */
public interface RevenueQueryPort {

    /**
     * One row per revenue document, as a parenthesised derived table (alias it). Columns:
     * {@code source} ({@code SALE} | {@code INVOICE} | {@code CREDIT_NOTE}), {@code doc_id}, {@code store_id}
     * (null for documents: invoices carry no store yet), {@code employee_id} (seller: the sale's actor, the invoice's
     * creator; a credit note's is its invoice's), {@code occurred_at}, {@code created_at} (arrival on the server),
     * {@code payment_mode} (sales only), {@code total_amount} (bigint, negative for a credit note) and
     * {@code doc_count} (1 for a sale or an invoice, 0 for a credit note — count transactions with
     * {@code SUM(doc_count)}).
     */
    String documents();

    /**
     * One row per revenue line, as a parenthesised derived table (alias it). Columns: {@code source}, {@code doc_id},
     * {@code store_id}, {@code employee_id}, {@code occurred_at}, {@code created_at}, {@code product_id} (null for an
     * invoice line without product — counted in revenue, never in a per-product margin), {@code product_name},
     * {@code quantity} (numeric, negative for a credit-note line), {@code subtotal} and {@code price_amount} (bigint,
     * the line amount — for a sale {@code subtotal} and {@code applied_unit_price × quantity}, for a document the line
     * total TTC; negative for a credit-note line).
     */
    String lines();
}
