package com.keevo.messaging.notification.domain.model;

/**
 * PushSendResult — outcome of a counted multicast send (Story 9-5, global push).
 *
 * @param successCount tokens FCM accepted
 * @param failureCount tokens FCM rejected (unregistered tokens included) or of a chunk that threw
 * @param chunks       multicast chunks attempted (at most 500 tokens each)
 * @param failedChunks chunks whose send threw (all their tokens counted as failures)
 */
public record PushSendResult(int successCount, int failureCount, int chunks, int failedChunks) {

    public static final PushSendResult EMPTY = new PushSendResult(0, 0, 0, 0);

    public PushSendResult plus(PushSendResult other) {
        return new PushSendResult(successCount + other.successCount, failureCount + other.failureCount,
                chunks + other.chunks, failedChunks + other.failedChunks);
    }

    /** True when at least one chunk was attempted and every one of them threw. */
    public boolean allChunksFailed() {
        return chunks > 0 && failedChunks == chunks;
    }
}
