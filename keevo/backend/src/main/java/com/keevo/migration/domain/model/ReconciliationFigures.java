package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.8 — the figures a reconciliation compares: what Bref gives ({@link Expected}, computed from the snapshot by
 * the documented import rules) and what Keevo holds ({@link Actual}, aggregate reads of the imported tenant). Only
 * aggregates, plus the sampled documents' fields (compared, never displayed when personal).
 */
public final class ReconciliationFigures {

    private ReconciliationFigures() {
    }

    /** Count keys shared by both sides. */
    public static final String STORES = "stores";
    public static final String OWNER = "owner";
    public static final String TEAM = "team";
    public static final String PRODUCTS = "products";
    public static final String CLIENTS = "clients";
    public static final String LINES = "lines";
    public static final String PAYMENTS = "payments";
    public static final String EXPENSES = "expenses";
    /** Live documents per kind and status: {@code documents:<KIND>:<STATUS>}; deleted ones under {@link #DELETED_DOCUMENTS}. */
    public static final String DOCUMENTS = "documents:";
    public static final String DELETED_DOCUMENTS = "documents:deleted";

    public static String documents(String kind, String status) {
        return DOCUMENTS + kind + ":" + status;
    }

    /** A shop (Keevo store id = Bref shop id) and a civil month {@code YYYY-MM}. */
    public record Bucket(UUID store, String month) {
    }

    /**
     * A sum and the number of amounts it adds (documents for the sales, rounded amounts for the collected and
     * outstanding sums): the tolerance basis.
     */
    public record Amount(BigDecimal sum, long items) {

        public static final Amount ZERO = new Amount(BigDecimal.ZERO, 0);

        public Amount add(BigDecimal value) {
            return new Amount(sum.add(value), items + 1);
        }

        public Amount plus(Amount other) {
            return new Amount(sum.add(other.sum), items + other.items);
        }
    }

    /**
     * A count expected in Keevo: {@code bref − explained}.
     *
     * @param explanation French reason of the rows Keevo holds differently (merges, rows left out), nullable
     */
    public record ExpectedCount(String key, String label, long bref, long explained, String explanation) {

        public long expected() {
            return bref - explained;
        }
    }

    /** The compared fields of a sampled document, in report order. */
    public enum SampleField {
        KIND("Type", false),
        STATUS("Statut", false),
        NUMBER("Numéro", false),
        ISSUE_DATE("Date d'émission", false),
        STORE("Boutique", false),
        CLIENT_NAME("Nom du client", true),
        SUBTOTAL("Sous-total", false),
        DISCOUNT("Remise", false),
        DELIVERY("Frais de livraison", false),
        TAX("TVA", false),
        TOTAL("Total TTC", false),
        LINE_COUNT("Nombre de lignes", false),
        LINES_TOTAL("Total des lignes HT", false),
        PAYMENT_COUNT("Nombre d'encaissements et d'échéances", false),
        PAID("Montant encaissé", false),
        PUBLIC_LINK("Lien public", true),
        DELETED("Supprimé", false);

        private final String label;
        /** Personal or technical value: compared, never displayed. */
        private final boolean hidden;

        SampleField(String label, boolean hidden) {
            this.label = label;
            this.hidden = hidden;
        }

        public String label() {
            return label;
        }

        public boolean hidden() {
            return hidden;
        }
    }

    /**
     * A sampled document: its display label (printed number or « Devis n ») and its expected field values (raw: codes,
     * ISO dates, store ids, francs; {@code ""} for none).
     */
    public record SampleDocument(UUID id, String label, Map<SampleField, String> fields) {
    }

    /** A Bref public token to find in the legacy link registry, pointing to this document. */
    public record ExpectedLink(UUID token, UUID documentId, String label) {
    }

    /** What the legacy link registry holds for a token. */
    public record LinkState(UUID documentId, boolean sameTenant, boolean documentLive) {
    }

    /** The plan in force at the import, its expiry ({@code null} = none) and the account credit. */
    public record ExpectedSubscription(String planCode, Instant expiresAt, long creditXaf) {
    }

    /** The Keevo plan, expiry and credit balance ({@code planCode} null when the tenant has no subscription row). */
    public record ActualSubscription(String planCode, Instant expiresAt, long creditXaf) {
    }

    /** Records of one (source table, target table) pair of the owner. */
    public record RecordCount(long rows, long targets, long noted, Instant firstAt) {
    }

    /**
     * What {@code migration_records} says of the owner: per pair, rows / distinct targets / rows with a note, and the
     * Bref accounts that have an account, link or invitation record.
     */
    public record RecordsSummary(Map<String, RecordCount> pairs, Set<UUID> recordedAccounts) {

        public static String pair(String sourceTable, String targetTable) {
            return sourceTable + "|" + targetTable;
        }

        public RecordCount get(String sourceTable, String targetTable) {
            return pairs.getOrDefault(pair(sourceTable, targetTable), new RecordCount(0, 0, 0, null));
        }
    }

    /** The Bref side. */
    public record Expected(List<ExpectedCount> counts, Map<Bucket, Amount> sales, Amount collected,
                           Map<String, BigDecimal> expenses, Amount outstanding, List<SampleDocument> sample,
                           List<ExpectedLink> links, ExpectedSubscription subscription, Map<UUID, String> storeLabels) {
    }

    /** The Keevo side. */
    public record Actual(Map<String, Long> counts, Map<Bucket, Amount> sales, Amount collected,
                         Map<String, BigDecimal> expenses, Amount outstanding,
                         Map<UUID, Map<SampleField, String>> sample, Map<UUID, LinkState> links,
                         ActualSubscription subscription) {
    }
}
