package com.keevo.commerce.api;

import com.keevo.shared.event.catalog.sale.DayClosedEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * ClosureWindow — which sales a day closure (and its end-of-day report) covers.
 * Pure Java — no Spring/JPA imports. Part of the published {@code commerce :: api} (Story 12.2c):
 * reporting builds its end-of-day / weekly windows with it.
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

    /**
     * Commit-safety margin (v1s-13-13): a sale's {@code createdAt} is stamped before its
     * transaction commits, so a sale stamped just before a closure/report ran may become
     * visible only after its queries. The arrival limit of a closure/report is therefore
     * {@code now − margin}, persisted as the anchor the next one chains on: a sale received in
     * the last {@code margin} is counted by the next closure/report as a late arrival.
     * Residual: a sale whose stamping-to-commit exceeds the margin (sale transactions are
     * single-operation, well under a second).
     */
    public static final Duration COMMIT_SAFETY_MARGIN = Duration.ofSeconds(5);

    /**
     * The sales a {@link DayClosedEvent} covers (Story 12.3: the catalogue event carries the window
     * bounds only; this replaces its former {@code window()} accessor, same values).
     */
    public static ClosureWindow of(DayClosedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new ClosureWindow(event.windowStart(), event.windowEnd(), event.lateFrom(), event.receivedUntil());
    }

    /** The arrival limit ({@code receivedUntil}) of a closure/report generated at {@code now}. */
    public static Instant receivedLimit(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return now.minus(COMMIT_SAFETY_MARGIN);
    }

    /** Day closures are dated in West Africa Time (UTC+1, no DST). */
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    /**
     * The window of closure {@code closure} following {@code previous} (Story v1s-18-3) — the one
     * definition shared by {@code CloseDayService} (at closing time) and the « Fin de journée »
     * PDF (afterwards), so both cover the same sales.
     *
     * @param closure  the closure (its {@code receivedAt} bounds the late sales it covers)
     * @param previous the store's closure just before it, if any
     */
    public static ClosureWindow forClosure(DayClosureView closure, Optional<DayClosureView> previous) {
        Objects.requireNonNull(closure, "closure must not be null");
        return forClosure(closure.closedAt(), closure.receivedAt(), previous);
    }

    /**
     * Same as {@link #forClosure(DayClosureView, Optional)} for a closure not built yet: with a
     * previous closure P, {@code (P.closedAt + 1µs, closedAt, P.receivedAt, receivedAt)} (the range
     * is inclusive, so P's own instant is skipped); without one, from WAT midnight of
     * {@code closedAt}'s day with no late sales.
     */
    public static ClosureWindow forClosure(Instant closedAt, Instant receivedAt, Optional<DayClosureView> previous) {
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        return previous
                .map(p -> new ClosureWindow(p.closedAt().plus(1, ChronoUnit.MICROS), closedAt,
                        p.receivedAt(), receivedAt))
                .orElseGet(() -> new ClosureWindow(
                        closedAt.atZone(WAT).toLocalDate().atStartOfDay(WAT).toInstant(), closedAt, null, receivedAt));
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
