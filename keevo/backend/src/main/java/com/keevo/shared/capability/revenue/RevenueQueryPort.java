package com.keevo.shared.capability.revenue;

/**
 * Story 20.9 (R4, FUS-FR24) — the single revenue source of every revenue figure: the owner dashboard, the daily and
 * weekly store reports, the seller ranking, the profitability reports (reporting), and since Story 20.14 the
 * clients export totals (crm) and the sales-trend alert (messaging). Those consumers never read {@code sales} /
 * {@code invoices} for revenue nor repeat the status rules: each one puts the derived tables of {@link Relations} in
 * its {@code FROM} and filters / groups them as it used to filter {@code sales}. (Platform analytics: 20.13.)
 *
 * <p>Story 20.14 — a core capability (declared in {@code shared}, implemented by {@code reporting}): crm and
 * messaging read it without a module edge to reporting (crm → reporting would close a cycle through commerce).
 *
 * <p>Revenue = COMPLETED sales ∪ counted invoices (kind INVOICE, ISSUED or PAID, {@code source_sale_id IS NULL} — a
 * POS sale bridged to an automatic invoice counts once, as the sale —, neither deleted nor superseded) − credit notes
 * of counted invoices − manual credit notes ({@code source_event IS NULL}) of bridged invoices. Quotes, drafts and
 * cancelled documents never count. A sale counts at {@code occurred_at}, an invoice at its civil issue date (WAT
 * midnight, FUS-NFR9), a credit note at its own {@code issued_at}.
 *
 * <p>The relations are parameter-free SQL; PostgreSQL pushes the consumer's predicates into each {@code UNION ALL}
 * branch. Resolve them <b>once per figure</b> (one call, then reuse both strings), inside the caller's transaction
 * (the tenant search_path).
 */
public interface RevenueQueryPort {

    /**
     * The revenue relations of the current tenant: sales always; invoices and credit notes when the invoicing tables
     * exist in the tenant schema (their data exists, whatever the module's current activation). Never provisions
     * anything, never fails for a missing module.
     */
    Relations current();

    /**
     * The relations for store-scoped figures (a store filter, or a query joined on {@code stores}). Story 20.14: the
     * same as {@link #current()} once the invoicing tables carry the document's store (invoicing V16) — documents
     * without store (made before) simply match no store; sales only for a tenant whose invoicing tables are older.
     */
    Relations storeScoped();

    /**
     * Story 20.11 — {@link #storeScoped()} for a reader of the VAT (the finance summary): the same {@code documents} /
     * {@code lines}, and {@code taxedDocuments} whose sales carry their VAT (bridge invoice or the seller's rate) when
     * the invoicing tables exist.
     */
    Relations storeScopedTaxed();

    /**
     * The two derived tables (each parenthesised — alias it).
     *
     * @param documents one row per revenue document: {@code source} ({@code SALE} | {@code INVOICE} |
     *                  {@code CREDIT_NOTE}), {@code doc_id}, {@code store_id} (a document's store, its invoice's for a
     *                  credit note; null for a document without store), {@code employee_id} (seller: the sale's actor,
     *                  the invoice's creator; a credit note's is its invoice's — may be null for a document),
     *                  {@code occurred_at}, {@code created_at}, {@code payment_mode} (sales only),
     *                  {@code total_amount} (bigint, negative for a credit note), {@code doc_count} (1 for a sale or
     *                  an invoice, 0 for a credit note); Story 20.14: {@code client_id} (the CRM client, its
     *                  invoice's for a credit note; null when none) and {@code recorded_at} (the instant of the
     *                  transaction: a sale's {@code occurred_at}, a document's {@code issued_at} — for hourly figures);
     *                  Story 20.11: {@code tax_amount} (bigint VAT inside {@code total_amount}: an invoice's
     *                  {@code total_vat}, minus a credit note's; 0 for a sale here — see {@code taxedDocuments}) and
     *                  {@code paid_amount} (the settled part, Bref's « payé »: a sale's total, a PAID invoice's total,
     *                  minus a credit note of a PAID invoice; 0 otherwise)
     * @param lines     one row per revenue line: {@code source}, {@code doc_id}, {@code store_id},
     *                  {@code employee_id}, {@code occurred_at}, {@code created_at}, {@code product_id} (null for an
     *                  invoice line without product — revenue, never a per-product margin), {@code product_name},
     *                  {@code quantity} (numeric, negative for a credit-note line), {@code subtotal} and
     *                  {@code price_amount} (bigint line amount; a document line's total TTC; negative for a credit
     *                  note)
     * @param taxedDocuments Story 20.11 — the same rows and columns as {@code documents}, except a sale's
     *                  {@code tax_amount}: the VAT of its active automatic invoice (POS bridge), else — for a
     *                  VAT-registered seller — {@code TTC − HALF_UP(TTC × 100 / (100 + rate))}, else 0, when the
     *                  invoicing tables exist. Revenue excl. VAT = {@code total_amount − tax_amount} (Bref's
     *                  {@code total − tax}). Costs two left joins per sale (unique index, settings row) — read it only
     *                  for VAT.
     */
    record Relations(String documents, String lines, String taxedDocuments) {

        /** Without a separate taxed relation: {@code taxedDocuments} = {@code documents} (sales carry no VAT). */
        public Relations(String documents, String lines) {
            this(documents, lines, documents);
        }
    }
}
