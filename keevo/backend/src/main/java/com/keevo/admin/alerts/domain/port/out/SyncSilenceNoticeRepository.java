package com.keevo.admin.alerts.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** SyncSilenceNoticeRepository — {@code public.sync_silence_notices} (Story 9-5, FR81). */
public interface SyncSilenceNoticeRepository {

    /** Latest push the last notice was sent for, empty when the company was never notified. */
    Optional<Instant> lastPushAtNotice(UUID tenantId);

    /** Records (or replaces) the notice of the current silence streak. */
    void recordNotice(UUID tenantId, Instant notifiedAt, Instant lastPushAt);

    /** Removes the notice (the message could not be sent: retried on the next run). */
    void deleteNotice(UUID tenantId);
}
