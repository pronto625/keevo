package com.keevo.messaging.api;


import java.util.List;
import java.util.UUID;

/**
 * Port — send push notifications to users of a given tenant.
 *
 * <p>Implementations are best-effort: callers MUST handle exceptions independently
 * and MUST NOT roll back their own transaction if notification delivery fails.
 *
 * <p>Published in {@code messaging :: api} (Story 12.2e); the implementations stay internal to
 * messaging: {@code FcmNotificationAdapter} is the {@code @Primary} implementation when FCM is
 * enabled (Story 8.0); otherwise {@code LoggingNotificationAdapter} (logs only) is the one wired.
 */
public interface NotificationPort {

    /**
     * Notify all OWNER-role users of the given tenant.
     *
     * @param tenantId  tenant schema name (from {@code TenantContext})
     * @param payload   rich payload with type, title, body, deepLink, metadata
     */
    void notifyOwners(String tenantId, NotificationPayload payload);

    /**
     * Notify a specific set of users identified by their user IDs.
     *
     * <p>HF-2 AC2: used for recipient strategies that include specific employees
     * (e.g., employees assigned to the destination store of a transfer).
     *
     * @param tenantId  tenant schema name
     * @param userIds   explicit list of user IDs to target; empty list is a no-op
     * @param payload   rich notification payload
     */
    void notifyUsers(String tenantId, List<UUID> userIds, NotificationPayload payload);

    /**
     * True when push notifications can actually be delivered (FCM configured and initialised).
     * Story 9-5 — a global push is marked FAILED instead of SENT when this is false.
     */
    boolean isPushAvailable();

    /**
     * Counted multicast send to explicit device tokens of one tenant (Story 9-5, global push):
     * chunks of at most {@value #MULTICAST_CHUNK_SIZE} tokens; tokens FCM reports as unregistered are
     * deleted from the tenant's {@code device_tokens}. Never throws: a chunk that throws is counted in
     * {@link PushSendResult#failedChunks()} with all its tokens as failures.
     *
     * @param tenantId tenant schema name (token cleanup runs in that schema)
     * @param tokens   device tokens; empty list → {@link PushSendResult#EMPTY}
     */
    PushSendResult sendToTokens(String tenantId, List<String> tokens, NotificationPayload payload);

    /** FCM multicast limit. */
    int MULTICAST_CHUNK_SIZE = 500;
}
