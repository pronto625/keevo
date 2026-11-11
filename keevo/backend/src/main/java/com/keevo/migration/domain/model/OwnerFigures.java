package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Story 24.9 — the figures of one processed owner, kept on its cutover line so the cutover report sums them in SQL
 * (and keeps them after a rollback purged the batches): counts and sums as French label → (expected from Bref, held by
 * Keevo), and the anomalies the import noted (French label → rows).
 */
public record OwnerFigures(Map<String, Pair> counts, Map<String, Pair> sums, Map<String, Long> anomalies) {

    public static final OwnerFigures NONE = new OwnerFigures(Map.of(), Map.of(), Map.of());

    public OwnerFigures {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
        sums = sums == null ? Map.of() : Map.copyOf(sums);
        anomalies = anomalies == null ? Map.of() : Map.copyOf(anomalies);
    }

    /** The same figures with the anomalies of the import batch. */
    public OwnerFigures withAnomalies(Map<String, Long> notes) {
        return new OwnerFigures(counts, sums, notes);
    }

    /** Expected (from Bref) and actual (Keevo) values of one figure. */
    public record Pair(BigDecimal expected, BigDecimal actual) {

        public Pair plus(Pair other) {
            return new Pair(expected.add(other.expected), actual.add(other.actual));
        }
    }
}
