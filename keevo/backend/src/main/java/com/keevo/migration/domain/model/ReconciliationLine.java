package com.keevo.migration.domain.model;

import java.math.BigDecimal;

/**
 * Story 24.8 — one compared figure of a reconciliation check: what Bref gives (expected in Keevo after the documented
 * mapping), what Keevo holds, the difference and the tolerance (numeric figures only), and a French explanation.
 * Labels and values never carry an id nor personal data (shops by ordinal, documents by their printed number).
 *
 * @param expected   the expected Keevo value, as displayed (amount, count, date, label)
 * @param actual     the value Keevo holds, as displayed
 * @param difference {@code actual − expected} for a numeric figure, else {@code null}
 * @param tolerance  the accepted absolute difference for a numeric figure, else {@code null}
 */
public record ReconciliationLine(String label, String expected, String actual, BigDecimal difference,
                                 BigDecimal tolerance, Status status, String statusLabel, String explanation) {

    /** The verdict of a line (and, worst first, of a check). */
    public enum Status {
        OK("Conforme"),
        /** A difference within tolerance or accounted for by a documented rule (merges, rounding, rows left out). */
        EXPLAINED("Écart expliqué"),
        GAP("Écart hors tolérance");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static ReconciliationLine of(String label, String expected, String actual, BigDecimal difference,
                                        BigDecimal tolerance, Status status, String explanation) {
        return new ReconciliationLine(label, expected, actual, difference, tolerance, status, status.label(),
                explanation);
    }
}
