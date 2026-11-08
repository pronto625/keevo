package com.keevo.reporting.revenue.adapter.out.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Story 20.9 (R4) — the SQL of the revenue source ({@link com.keevo.shared.capability.revenue.RevenueQueryPort}),
 * written once. Pure: which branches are present is the caller's choice ({@link ProvisionedRevenueSource} decides it
 * from the tables present in the tenant schema).
 *
 * <p>The window predicates of the consumers are pushed by PostgreSQL into every {@code UNION ALL} branch: all-stores
 * windows on sales use the partial index {@code idx_sales_completed_occurred_at} (pos V2), store-scoped ones the
 * {@code (store_id, occurred_at)} index; counted invoices the partial expression index
 * {@code idx_invoices_revenue_day} (invoicing V14 — same expression as {@link #INVOICE_DAY}); credit notes
 * {@code idx_credit_notes_issued_at}; lines join on the unique {@code (invoice_id, position)} /
 * {@code (credit_note_id, position)} keys.
 *
 * <p>Story 20.14 — documents carry their store ({@code invoices.store_id}, V16; a credit note its invoice's): store
 * windows use {@code idx_invoices_store_revenue_day}, hourly store activity on {@code recorded_at}
 * {@code idx_invoices_counted_issued_at}, per-client totals {@code idx_invoices_client} / {@code idx_sales_client_id}.
 */
public final class RevenueRelations {

    /** Civil issue date of an invoice as the WAT midnight instant (FUS-NFR9) — the V14 index expression. */
    static final String INVOICE_DAY = "(i.issue_date::timestamp AT TIME ZONE 'Africa/Lagos')";

    /**
     * A counted invoice: a real invoice (not a quote), issued or paid, not created from a POS sale (that sale already
     * counts), neither soft-deleted (drafts only) nor superseded by a sale correction. Same predicate as the V14
     * partial index.
     */
    static final String COUNTED_INVOICE = "i.kind = 'INVOICE' AND i.status IN ('ISSUED', 'PAID') "
            + "AND i.source_sale_id IS NULL AND i.deleted_at IS NULL AND i.superseded_at IS NULL";

    /**
     * A deducted credit note: one of a counted invoice, or a <b>manual</b> one ({@code source_event IS NULL}) of an
     * invoice bridged from a POS sale — the sale stays COMPLETED, so the refund must come off here. Automatic credit
     * notes of bridged invoices (sale cancelled / corrected, {@code source_event} set) are already reflected by the
     * sale itself and never count.
     */
    static final String DEDUCTED_CREDIT_NOTE = "i.kind = 'INVOICE' AND i.status IN ('ISSUED', 'PAID') "
            + "AND i.deleted_at IS NULL AND i.superseded_at IS NULL "
            + "AND (i.source_sale_id IS NULL OR cn.source_event IS NULL)";

    /** Story 20.14 — the invoice's store (invoicing V16), or none for a schema below V16. */
    private static final String INVOICE_STORE = "i.store_id";
    private static final String NO_STORE = "NULL::uuid";

    private static final String SALE_DOCUMENTS =
            "SELECT 'SALE'::text AS source, s.id AS doc_id, s.store_id AS store_id, s.employee_id AS employee_id, "
            + "s.occurred_at AS occurred_at, s.created_at AS created_at, s.payment_mode::text AS payment_mode, "
            + "s.total_amount::bigint AS total_amount, 1 AS doc_count, s.client_id AS client_id, "
            + "s.occurred_at AS recorded_at, 0::bigint AS tax_amount, s.total_amount::bigint AS paid_amount "
            + "FROM sales s WHERE s.status = 'COMPLETED'";

    /**
     * Story 20.11 — the sales branch of the taxed relations (invoicing tables present): the same rows, with the sale's
     * VAT as {@code tax_amount} (a POS sale stores no VAT itself):
     * <ul>
     *   <li>bridged to an active automatic invoice (POS bridge) → that invoice's {@code total_vat};</li>
     *   <li>otherwise, for a VAT-registered seller ({@code invoicing_settings.vat_registered}) → extracted from the
     *       VAT-inclusive amount with the seller's rate, Keevo's per-line rule:
     *       {@code TTC − HALF_UP(TTC × 100 / (100 + rate))};</li>
     *   <li>otherwise 0.</li>
     * </ul>
     * Set-based: a left join on the unique partial index on {@code source_sale_id} (active invoices: at most one row per
     * sale, never a duplicate) and on the single settings row ({@code id = 1}). Kept out of {@link #documents}:
     * PostgreSQL does not remove the joins from a {@code UNION ALL} branch whose {@code tax_amount} is unread (checked
     * by EXPLAIN), so only the taxed relations pay them.
     */
    private static final String SALE_DOCUMENTS_WITH_VAT =
            "SELECT 'SALE'::text AS source, s.id AS doc_id, s.store_id AS store_id, s.employee_id AS employee_id, "
            + "s.occurred_at AS occurred_at, s.created_at AS created_at, s.payment_mode::text AS payment_mode, "
            + "s.total_amount::bigint AS total_amount, 1 AS doc_count, s.client_id AS client_id, "
            + "s.occurred_at AS recorded_at, "
            + "(CASE WHEN bi.id IS NOT NULL THEN bi.total_vat "
            + "WHEN st.vat_registered THEN s.total_amount - ROUND(s.total_amount * 100.0 / (100 + st.vat_rate)) "
            + "ELSE 0 END)::bigint AS tax_amount, s.total_amount::bigint AS paid_amount "
            + "FROM sales s LEFT JOIN invoices bi ON bi.source_sale_id = s.id AND bi.superseded_at IS NULL "
            + "AND bi.kind = 'INVOICE' AND bi.status IN ('ISSUED', 'PAID') AND bi.deleted_at IS NULL "
            + "LEFT JOIN invoicing_settings st ON st.id = 1 "
            + "WHERE s.status = 'COMPLETED'";

    private static String invoiceDocuments(String store) {
        return "SELECT 'INVOICE'::text, i.id, " + store + ", i.created_by, " + INVOICE_DAY + ", i.issued_at, "
                + "NULL::text, i.total_ttc::bigint, 1, i.client_id, i.issued_at, i.total_vat::bigint, "
                + "(CASE WHEN i.status = 'PAID' THEN i.total_ttc ELSE 0 END)::bigint "
                + "FROM invoices i WHERE " + COUNTED_INVOICE;
    }

    private static String creditNoteDocuments(String store) {
        return "SELECT 'CREDIT_NOTE'::text, cn.id, " + store + ", i.created_by, cn.issued_at, cn.issued_at, "
                + "NULL::text, -cn.total_ttc::bigint, 0, i.client_id, cn.issued_at, -cn.total_vat::bigint, "
                + "(CASE WHEN i.status = 'PAID' THEN -cn.total_ttc ELSE 0 END)::bigint "
                + "FROM credit_notes cn JOIN invoices i ON i.id = cn.invoice_id WHERE " + DEDUCTED_CREDIT_NOTE;
    }

    private static final String EMPTY_DOCUMENTS =
            "SELECT NULL::text AS source, NULL::uuid AS doc_id, NULL::uuid AS store_id, NULL::uuid AS employee_id, "
            + "NULL::timestamptz AS occurred_at, NULL::timestamptz AS created_at, NULL::text AS payment_mode, "
            + "0::bigint AS total_amount, 0 AS doc_count, NULL::uuid AS client_id, "
            + "NULL::timestamptz AS recorded_at, 0::bigint AS tax_amount, 0::bigint AS paid_amount WHERE FALSE";

    private static final String SALE_LINES =
            "SELECT 'SALE'::text AS source, s.id AS doc_id, s.store_id AS store_id, s.employee_id AS employee_id, "
            + "s.occurred_at AS occurred_at, s.created_at AS created_at, si.product_id AS product_id, "
            + "si.product_name::text AS product_name, si.quantity::numeric AS quantity, "
            + "si.subtotal::bigint AS subtotal, (si.applied_unit_price::bigint * si.quantity) AS price_amount "
            + "FROM sale_items si JOIN sales s ON s.id = si.sale_id WHERE s.status = 'COMPLETED'";

    private static String invoiceLines(String store) {
        return "SELECT 'INVOICE'::text, i.id, " + store + ", i.created_by, " + INVOICE_DAY + ", i.issued_at, "
                + "it.product_id, it.description::text, it.quantity::numeric, it.total_ttc::bigint, "
                + "it.total_ttc::bigint "
                + "FROM invoice_items it JOIN invoices i ON i.id = it.invoice_id WHERE " + COUNTED_INVOICE;
    }

    /** Credit-note lines: negative, product taken from the credited invoice line. */
    private static String creditNoteLines(String store) {
        return "SELECT 'CREDIT_NOTE'::text, cn.id, " + store + ", i.created_by, cn.issued_at, cn.issued_at, "
                + "it.product_id, cni.description::text, -cni.quantity::numeric, -cni.total_ttc::bigint, "
                + "-cni.total_ttc::bigint "
                + "FROM credit_note_items cni JOIN credit_notes cn ON cn.id = cni.credit_note_id "
                + "JOIN invoices i ON i.id = cn.invoice_id "
                + "LEFT JOIN invoice_items it ON it.invoice_id = i.id AND it.position = cni.invoice_position "
                + "WHERE " + DEDUCTED_CREDIT_NOTE;
    }

    private static final String EMPTY_LINES =
            "SELECT NULL::text AS source, NULL::uuid AS doc_id, NULL::uuid AS store_id, NULL::uuid AS employee_id, "
            + "NULL::timestamptz AS occurred_at, NULL::timestamptz AS created_at, NULL::uuid AS product_id, "
            + "NULL::text AS product_name, 0::numeric AS quantity, 0::bigint AS subtotal, 0::bigint AS price_amount "
            + "WHERE FALSE";

    /** Sales only — the relation of a tenant without documents (and of the unit tests' default source). */
    public static final String POS_DOCUMENTS = documents(true, false);
    /** Sales lines only. */
    public static final String POS_LINES = lines(true, false);

    private RevenueRelations() {
    }

    /**
     * The documents derived table with the requested branches (an empty relation when none); documents without
     * store (a schema below invoicing V16).
     */
    public static String documents(boolean sales, boolean invoicing) {
        return documents(sales, invoicing, false);
    }

    /**
     * Story 20.14 — same, documents with their store when {@code invoiceStore} (the schema has invoicing V16
     * {@code invoices.store_id}); a credit note carries its invoice's store.
     */
    public static String documents(boolean sales, boolean invoicing, boolean invoiceStore) {
        return documents(sales ? SALE_DOCUMENTS : null, invoicing, invoiceStore);
    }

    /**
     * Story 20.11 — the documents derived table whose sales carry their VAT ({@link #SALE_DOCUMENTS_WITH_VAT}) when
     * {@code invoicing}; otherwise the same as {@link #documents(boolean, boolean, boolean)}.
     */
    public static String taxedDocuments(boolean sales, boolean invoicing, boolean invoiceStore) {
        return documents(sales ? (invoicing ? SALE_DOCUMENTS_WITH_VAT : SALE_DOCUMENTS) : null, invoicing,
                invoiceStore);
    }

    /**
     * Story 20.11 — sales only, each with its VAT: the taxed store-scoped relation of a tenant whose invoicing tables
     * have no documents' store yet (below V16).
     */
    public static String taxedSales() {
        return wrap(List.of(SALE_DOCUMENTS_WITH_VAT));
    }

    private static String documents(String salesBranch, boolean invoicing, boolean invoiceStore) {
        String store = invoiceStore ? INVOICE_STORE : NO_STORE;
        List<String> branches = new ArrayList<>(3);
        if (salesBranch != null) {
            branches.add(salesBranch);
        }
        if (invoicing) {
            if (branches.isEmpty()) {
                branches.add(EMPTY_DOCUMENTS); // the first branch names the columns
            }
            branches.add(invoiceDocuments(store));
            branches.add(creditNoteDocuments(store));
        }
        return wrap(branches.isEmpty() ? List.of(EMPTY_DOCUMENTS) : branches);
    }

    /** The lines derived table with the requested branches (an empty relation when none); lines without store. */
    public static String lines(boolean sales, boolean invoicing) {
        return lines(sales, invoicing, false);
    }

    /** Story 20.14 — same, document lines with their document's store when {@code invoiceStore}. */
    public static String lines(boolean sales, boolean invoicing, boolean invoiceStore) {
        String store = invoiceStore ? INVOICE_STORE : NO_STORE;
        List<String> branches = new ArrayList<>(3);
        if (sales) {
            branches.add(SALE_LINES);
        }
        if (invoicing) {
            if (branches.isEmpty()) {
                branches.add(EMPTY_LINES);
            }
            branches.add(invoiceLines(store));
            branches.add(creditNoteLines(store));
        }
        return wrap(branches.isEmpty() ? List.of(EMPTY_LINES) : branches);
    }

    private static String wrap(List<String> branches) {
        return "(" + String.join(" UNION ALL ", branches) + ")";
    }
}
