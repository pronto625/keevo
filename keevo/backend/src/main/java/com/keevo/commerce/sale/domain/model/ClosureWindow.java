package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * ClosureWindow — which sales a day closure (and its end-of-day report) covers.
 * Pure Java — no Spring/JPA imports.
 *
 * <p>A sale belongs to the first closure, in arrival order, that it happened before
 * and reached the server before. For a closure C following closure P, that is:
 * <pre>
 *   occurredAt &lt;= C.closedAt AND createdAt &lt;= C.receivedAt
 *   AND (occurredAt &gt; P.closedAt OR createdAt &gt; P.receivedAt)
 * </pre>
 * {@code createdAt} is stamped by the server when the sale is recorded, so a sale
 * made offline before P but synced after it falls into C instead of no closure.
 * Every sale belongs to exactly one closure because closures of a store only move
 * forward (an older closure pushed late is refused).
 *
 * @param start         first sale time covered (just after P.closedAt, else midnight)
 * @param end           last sale time covered (C.closedAt)
 * @param lateFrom      sales received after this instant are covered even if they
 *                      happened before {@code start} (P.receivedAt); {@link #NEVER} for none
 * @param receivedUntil sales received after this instant belong to a later closure
 *                      (C.receivedAt); {@link #NEVER} for no limit
 */
public record ClosureWindow(Instant start, Instant end, Instant lateFrom, Instant receivedUntil) {

    /** Far-future instant standing for "no bound" (keeps the SQL predicate uniform). */
    public static final Instant NEVER = Instant.parse("9999-12-31T00:00:00Z");

    public ClosureWindow {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        lateFrom = lateFrom != null ? lateFrom : NEVER;
        receivedUntil = receivedUntil != null ? receivedUntil : NEVER;
    }

    /** Plain time window on sale time only (manual and weekly reports). */
    public static ClosureWindow between(Instant start, Instant end) {
        return new ClosureWindow(start, end, NEVER, NEVER);
    }

    /** Whether a sale that happened at {@code occurredAt} and reached the server at {@code createdAt} is covered. */
    public boolean covers(Instant occurredAt, Instant createdAt) {
        return !occurredAt.isAfter(end)
                && !createdAt.isAfter(receivedUntil)
                && (!occurredAt.isBefore(start) || createdAt.isAfter(lateFrom));
    }
}
