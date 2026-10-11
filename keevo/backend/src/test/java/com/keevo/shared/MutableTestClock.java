package com.keevo.shared;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe, manually advanced {@link Clock} for TTL / window tests (Story 12.12). */
public final class MutableTestClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableTestClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
}
