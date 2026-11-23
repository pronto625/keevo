package com.keevo.upload.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 22.19 (D10) — at most {@value #PER_MINUTE} image uploads per company and per minute (fixed one-minute window,
 * in memory, one instance — same model as the request ceiling of {@code RateLimitFilter}). Each upload is a paid
 * Cloudinary operation: a runaway client cannot burn the quota.
 */
@Component
public class UploadRateLimiter {

    public static final int PER_MINUTE = 30;
    private static final long WINDOW_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int limit;

    @Autowired
    public UploadRateLimiter(Clock clock) {
        this(clock, PER_MINUTE);
    }

    UploadRateLimiter(Clock clock, int limit) {
        this.clock = clock;
        this.limit = limit;
    }

    /** Counts one upload of {@code tenant}; false when it is over the limit of the current minute. */
    public boolean tryAcquire(String tenant) {
        long now = clock.millis();
        Window w = windows.compute(tenant, (k, current) ->
                current == null || now - current.start >= WINDOW_MILLIS ? new Window(now, 1)
                        : new Window(current.start, current.count + 1));
        return w.count <= limit;
    }

    /** Gives back one upload of the current window (a failed upload is not counted). */
    public void release(String tenant) {
        windows.computeIfPresent(tenant, (k, w) -> new Window(w.start, Math.max(0, w.count - 1)));
    }

    private record Window(long start, int count) {
    }
}
