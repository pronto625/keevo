package com.keevo.shared.application.port;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * AuditContext — request-channel facts for audit writes that are not domain facts
 * (Story v1s-18-6).
 *
 * <p>Pure Java — NO Spring imports. A {@link ThreadLocal} holder set by the sync push endpoint
 * around {@code pushBatch(...)}: domain events raised during the push are handled synchronously
 * on the same thread by {@code AuditEventListener}, which then records {@code source=SYNC} and the
 * pushing device. The previous value is always restored in {@code finally} (nested-safe), so a
 * pooled thread never leaks a sync context into a later request.
 */
public final class AuditContext {

    /** Sync channel marker with the device that pushed the batch (nullable). */
    public record SyncContext(String deviceId) {}

    private static final ThreadLocal<SyncContext> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    /** Run {@code work} with a sync context bound to the current thread, then restore the previous one. */
    public static <T> T runAsSync(String deviceId, Supplier<T> work) {
        SyncContext previous = CURRENT.get();
        CURRENT.set(new SyncContext(deviceId));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The sync context of the current thread, if inside {@link #runAsSync}. */
    public static Optional<SyncContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
