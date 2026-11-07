package com.keevo.reporting.revenue.adapter.out.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Story 20.9 (R4) — the SQL of the revenue source ({@link com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort}),
 * written once. Pure: which branches are present is the caller's choice ({@link ProvisionedRevenueSource} decides it
 * from the tables present in the tenant schema).
 *
 * <p>The window predicates of the consumers are pushed by PostgreSQL into every {@code UNION ALL} branch: all-stores
 * windows on sales use the partial index {@code idx_sales_completed_occurred_at} (pos V2), store-scoped ones the
 * {@code (store_id, occurred_at)} index; counted invoices the partial expression index
 * {@code idx_invoices_revenue_day} (invoicing V14 — same expression as {@link #INVOICE_DAY}); credit notes
 * {@code idx_credit_notes_issued_at}; lines join on the unique {@code (invoice_id, position)} /
 * {@code (credit_note_id, position)} keys.
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

    private static final String SALE_DOCUMENTS =
            "SELECT 'SALE'::text AS source, s.id AS doc_id, s.store_id AS store_id, s.employee_id AS employee_id, "
            + "s.occurred_at AS occurred_at, s.created_at AS created_at, s.payment_mode::text AS payment_mode, "
            + "s.total_amount::bigint AS total_amount, 1 AS doc_count "
            + "FROM sales s WHERE s.status = 'COMPLETED'";

    private static final String INVOICE_DOCUMENTS =
            "SELECT 'INVOICE'::text, i.id, NULL::uuid, i.created_by, " + INVOICE_DAY + ", i.issued_at, NULL::text, "
            + "i.total_ttc::bigint, 1 "
            + "FROM invoices i WHERE " + COUNTED_INVOICE;

    private static final String CREDIT_NOTE_DOCUMENTS =
            "SELECT 'CREDIT_NOTE'::text, cn.id, NULL::uuid, i.created_by, cn.issued_at, cn.issued_at, NULL::text, "
            + "-cn.total_ttc::bigint, 0 "
            + "FROM credit_notes cn JOIN invoices i ON i.id = cn.invoice_id WHERE " + DEDUCTED_CREDIT_NOTE;

    private static final String EMPTY_DOCUMENTS =
            "SELECT NULL::text AS source, NULL::uuid AS doc_id, NULL::uuid AS store_id, NULL::uuid AS employee_id, "
            + "NULL::timestamptz AS occurred_at, NULL::timestamptz AS created_at, NULL::text AS payment_mode, "
            + "0::bigint AS total_amount, 0 AS doc_count WHERE FALSE";

    private static final String SALE_LINES =
            "SELECT 'SALE'::text AS source, s.id AS doc_id, s.store_id AS store_id, s.employee_id AS employee_id, "
            + "s.occurred_at AS occurred_at, s.created_at AS created_at, si.product_id AS product_id, "
            + "si.product_name::text AS product_name, si.quantity::numeric AS quantity, "
            + "si.subtotal::bigint AS subtotal, (si.applied_unit_price::bigint * si.quantity) AS price_amount "
            + "FROM sale_items si JOIN sales s ON s.id = si.sale_id WHERE s.status = 'COMPLETED'";

    private static final String INVOICE_LINES =
            "SELECT 'INVOICE'::text, i.id, NULL::uuid, i.created_by, " + INVOICE_DAY + ", i.issued_at, "
            + "it.product_id, it.description::text, it.quantity::numeric, it.total_ttc::bigint, it.total_ttc::bigint "
            + "FROM invoice_items it JOIN invoices i ON i.id = it.invoice_id WHERE " + COUNTED_INVOICE;

    /** Credit-note lines: negative, product taken from the credited invoice line. */
    private static final String CREDIT_NOTE_LINES =
            "SELECT 'CREDIT_NOTE'::text, cn.id, NULL::uuid, i.created_by, cn.issued_at, cn.issued_at, "
            + "it.product_id, cni.description::text, -cni.quantity::numeric, -cni.total_ttc::bigint, "
            + "-cni.total_ttc::bigint "
            + "FROM credit_note_items cni JOIN credit_notes cn ON cn.id = cni.credit_note_id "
            + "JOIN invoices i ON i.id = cn.invoice_id "
            + "LEFT JOIN invoice_items it ON it.invoice_id = i.id AND it.position = cni.invoice_position "
            + "WHERE " + DEDUCTED_CREDIT_NOTE;

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

    /** The documents derived table with the requested branches (an empty relation when none). */
    public static String documents(boolean sales, boolean invoicing) {
        List<String> branches = new ArrayList<>(3);
        if (sales) {
            branches.add(SALE_DOCUMENTS);
        }
        if (invoicing) {
            if (branches.isEmpty()) {
                branches.add(EMPTY_DOCUMENTS); // the first branch names the columns
            }
            branches.add(INVOICE_DOCUMENTS);
            branches.add(CREDIT_NOTE_DOCUMENTS);
        }
        return wrap(branches.isEmpty() ? List.of(EMPTY_DOCUMENTS) : branches);
    }

    /** The lines derived table with the requested branches (an empty relation when none). */
    public static String lines(boolean sales, boolean invoicing) {
        List<String> branches = new ArrayList<>(3);
        if (sales) {
            branches.add(SALE_LINES);
        }
        if (invoicing) {
            if (branches.isEmpty()) {
                branches.add(EMPTY_LINES);
            }
            branches.add(INVOICE_LINES);
            branches.add(CREDIT_NOTE_LINES);
        }
        return wrap(branches.isEmpty() ? List.of(EMPTY_LINES) : branches);
    }

    private static String wrap(List<String> branches) {
        return "(" + String.join(" UNION ALL ", branches) + ")";
    }
}
