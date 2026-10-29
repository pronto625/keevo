package com.keevo.commerce.sale.domain;

import com.keevo.commerce.api.ClosureWindow;
import com.keevo.commerce.sale.domain.model.DayClosure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sale belongs to exactly one closure, including sales made offline before a closure
 * and synced after it (they go to the next closure instead of none).
 */
@DisplayName("ClosureWindow")
class ClosureWindowTest {

    private static final Instant T0 = Instant.parse("2026-10-10T00:00:00Z");

    private static Instant at(int hours) {
        return T0.plusSeconds(hours * 3600L);
    }

    // Closure P: closed at 20:00 day 1, received at 20:00. Closure C: closed at 20:00 day 2,
    // received at 20:00 day 2. Closure D: closed at 20:00 day 3.
    private final ClosureWindow p = new ClosureWindow(at(0), at(20), null, at(20));
    private final ClosureWindow c = new ClosureWindow(at(20).plusNanos(1000), at(44), at(20), at(44));
    private final ClosureWindow d = new ClosureWindow(at(44).plusNanos(1000), at(68), at(44), at(68));

    @Test
    @DisplayName("an online sale is covered by the closure of its day only")
    void onlineSale() {
        assertCoveredOnlyBy(at(10), at(10), p);
        assertCoveredOnlyBy(at(30), at(30), c);
    }

    @Test
    @DisplayName("a sale made before P but received after it goes to C")
    void lateOfflineSaleGoesToNextClosure() {
        assertCoveredOnlyBy(at(10), at(26), c);
    }

    @Test
    @DisplayName("a sale made before C.closedAt but received after C goes to D")
    void saleReceivedAfterClosureGoesToTheFollowingOne() {
        assertCoveredOnlyBy(at(40), at(50), d);
    }

    @Test
    @DisplayName("a sale made after C.closedAt goes to D even if received before C")
    void saleAfterClosureTimeGoesToTheFollowingOne() {
        assertCoveredOnlyBy(at(45), at(43), d);
    }

    @Test
    @DisplayName("a plain time window ignores the reception time")
    void plainWindow() {
        var window = ClosureWindow.between(at(0), at(20));
        assertThat(window.covers(at(10), at(500))).isTrue();
        assertThat(window.covers(at(21), at(10))).isFalse();
    }

    private void assertCoveredOnlyBy(Instant occurredAt, Instant createdAt, ClosureWindow expected) {
        for (ClosureWindow w : List.of(p, c, d)) {
            assertThat(w.covers(occurredAt, createdAt))
                    .as("window %s", w)
                    .isEqualTo(w == expected);
        }
    }

    // ── forClosure (v1s-18-3): one window definition for the closure and its PDF ─────────

    private static DayClosure closure(Instant closedAt, Instant receivedAt) {
        return new DayClosure(UUID.randomUUID(), UUID.randomUUID(), null, closedAt, null, false, "kv_t", receivedAt);
    }

    @Test
    @DisplayName("forClosure with a previous closure: (P.closedAt + 1µs, C.closedAt, P.receivedAt, C.receivedAt)")
    void forClosureWithPrevious() {
        DayClosure prev = closure(at(20), at(21));
        DayClosure c = closure(at(44), at(45));

        ClosureWindow w = ClosureWindow.forClosure(c.toView(), Optional.of(prev.toView()));

        assertThat(w).isEqualTo(new ClosureWindow(at(20).plusNanos(1000), at(44), at(21), at(45)));
        assertThat(w.covers(at(10), at(30))).as("made before P, received after it: late sale of C").isTrue();
    }

    @Test
    @DisplayName("forClosure without a previous closure: from WAT midnight of the closure's day, no late sales")
    void forClosureWithoutPrevious() {
        Instant closedAt = Instant.parse("2026-10-10T23:30:00Z"); // 00:30 WAT on the 11th
        DayClosure c = closure(closedAt, closedAt.plusSeconds(2));

        ClosureWindow w = ClosureWindow.forClosure(c.toView(), Optional.empty());

        assertThat(w).isEqualTo(new ClosureWindow(Instant.parse("2026-10-10T23:00:00Z"), closedAt, null,
                closedAt.plusSeconds(2)));
        assertThat(w.lateFrom()).isEqualTo(ClosureWindow.NEVER);
    }

    @Test
    @DisplayName("forClosure of a closure predating receivedAt uses closedAt as its reception time")
    void forClosureLegacyReceivedAt() {
        DayClosure c = closure(at(44), null);
        assertThat(ClosureWindow.forClosure(c.toView(), Optional.empty()).receivedUntil()).isEqualTo(at(44));
    }
}
