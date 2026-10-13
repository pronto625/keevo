package com.keevo.admin.notification.domain.port.out;

import com.keevo.admin.notification.domain.model.Announcement;
import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason;
import com.keevo.admin.notification.domain.model.PushTenantRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code public.global_notifications} (Story 9-5). */
public interface GlobalNotificationRepository {

    void insert(GlobalNotification notification);

    /** Rows created at or after {@code since}, newest first, with the target company name. */
    List<GlobalNotification> findCreatedSince(Instant since);

    Optional<GlobalNotification> findById(UUID id);

    /**
     * Atomically moves every SCHEDULED row due at {@code now} to SENDING ({@code claimed_at = now}) and
     * returns them ({@code UPDATE … RETURNING}): two concurrent callers never claim the same row.
     */
    List<GlobalNotification> claimDue(Instant now);

    /** Atomically ends FAILED / INTERRUPTED every row SENDING since before {@code claimedBefore}. */
    List<GlobalNotification> failStuck(Instant claimedBefore, Instant now);

    /** SCHEDULED → CANCELLED ({@code sent_at = now}); false when the row is not (or no longer) SCHEDULED. */
    boolean cancelIfScheduled(UUID id, Instant now);

    /** SENDING → SENT (reason null) / FAILED with the counts; {@code endedAt} stored in {@code sent_at}. */
    void complete(UUID id, boolean sent, GlobalNotificationFailureReason reason, int recipientCount,
                  int deliveredCount, int failureCount, Instant endedAt);

    /** Deletes the rows created before {@code cutoff} that are no longer SCHEDULED. */
    int purgeCreatedBefore(Instant cutoff);

    /**
     * SENT announcements ended at or after {@code since} that reach the company: ALL, its current plan
     * (PREMIUM / TRIAL / FREE) or itself (TENANT); oldest first.
     */
    List<Announcement> findSentFor(PushTenantRef tenant, Instant since);
}
