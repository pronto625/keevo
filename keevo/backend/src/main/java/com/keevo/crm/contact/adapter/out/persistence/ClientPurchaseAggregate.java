package com.keevo.crm.contact.adapter.out.persistence;

/**
 * Story 20.2 (reprise 11/11/2026) — the one definition of a client's purchase figures over the rows of the single
 * revenue source ({@code RevenueQueryPort.Relations#documents()}), read by the client profile
 * ({@link JdbcClientPurchaseStats}), the clients export and the marketing audience (21.11): count
 * {@code SUM(doc_count)} (a sale or an invoice = 1, a credit note = 0), net total {@code SUM(total_amount)} (credit
 * notes negative), last purchase {@code MAX(COALESCE(recorded_at, created_at))} over the purchases only (Story 20.14).
 * No purchase = 0, 0, {@code NULL}.
 */
public final class ClientPurchaseAggregate {

    public static final String COUNT = "purchase_count";
    public static final String TOTAL = "total_spent";
    public static final String LAST = "last_purchase_at";

    private ClientPurchaseAggregate() {
    }

    /**
     * The aggregate select list over the documents aliased {@code alias}, as the columns {@link #COUNT},
     * {@link #TOTAL} and {@link #LAST}.
     */
    public static String select(String alias) {
        return "COALESCE(SUM(" + alias + ".doc_count), 0) AS " + COUNT
                + ", COALESCE(SUM(" + alias + ".total_amount), 0) AS " + TOTAL
                + ", MAX(COALESCE(" + alias + ".recorded_at, " + alias + ".created_at)) FILTER (WHERE " + alias
                + ".doc_count > 0) AS " + LAST;
    }
}
