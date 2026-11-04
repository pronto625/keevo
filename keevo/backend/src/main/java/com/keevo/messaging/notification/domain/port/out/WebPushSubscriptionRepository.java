package com.keevo.messaging.notification.domain.port.out;

import com.keevo.messaging.notification.domain.model.WebPushSubscription;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Story 19.9b — {@code public.web_push_subscriptions}. */
public interface WebPushSubscriptionRepository {

    /**
     * Inserts the subscription, or updates the row of its endpoint when that row belongs to the same user or carries
     * the same {@code p256dh} and {@code auth} keys (same browser, account switched) — one atomic statement.
     *
     * @return false when the endpoint belongs to another user with other keys (nothing written)
     */
    boolean upsert(WebPushSubscription subscription);

    /** Deletes the caller's row of this endpoint; the number deleted (0 or 1). */
    int deleteByUserAndEndpoint(UUID userId, String endpoint);

    /** Every subscription of these users, in one query. */
    List<WebPushSubscription> findByUserIds(Collection<UUID> userIds);

    /** Deletes these subscriptions in one statement; the number deleted. */
    int deleteByIds(Collection<UUID> ids);
}
