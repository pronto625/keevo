package com.keevo.invoicing.invoice.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.8 — one request to send a document by one channel ({@code invoice_send_requests}), the durable outbox of
 * Bref's {@code invoice_outbox}: a queued offline sending (keyed on the sync {@code operationId} + channel, so a pushed
 * again operation never sends twice) or the log of an online one (written SENT / FAILED / UNKNOWN).
 *
 * @param operationId       sync operation id ({@code null} for an online sending)
 * @param recipientOverride number (WhatsApp / SMS) or address (email) typed by the user; {@code null} = the client's
 * @param attempts          deliveries tried so far
 * @param lastErrorCode     code of the last failure ({@code SMS_DELIVERY_FAILED}, {@code QUOTA_EXCEEDED}…)
 * @param nextAttemptAt     when a PENDING request is due
 */
public record InvoiceSendRequest(UUID id, UUID invoiceId, String operationId, SendChannel channel,
                                 String recipientOverride, SendStatus status, int attempts, String lastErrorCode,
                                 Instant nextAttemptAt, UUID createdBy, Instant createdAt, Instant updatedAt) {

    /** Bref's {@code MAX_ATTEMPTS}. */
    public static final int MAX_ATTEMPTS = 5;
    /** First retry delay (Bref polls every 30 s); doubled at each attempt: 30 s, 1, 2, 4 min. */
    public static final Duration FIRST_RETRY = Duration.ofSeconds(30);

    /** A new queued request (due now). */
    public static InvoiceSendRequest queued(UUID invoiceId, String operationId, SendChannel channel,
                                            String recipientOverride, UUID createdBy, Instant now) {
        return new InvoiceSendRequest(UUID.randomUUID(), invoiceId, operationId, channel, recipientOverride,
                SendStatus.PENDING, 0, null, now, createdBy, now, now);
    }

    /** The log of an online sending already attempted (one attempt, final status). */
    public static InvoiceSendRequest attempted(UUID invoiceId, SendChannel channel, String recipientOverride,
                                               SendStatus status, String errorCode, UUID createdBy, Instant now) {
        return new InvoiceSendRequest(UUID.randomUUID(), invoiceId, null, channel, recipientOverride, status, 1,
                errorCode, null, createdBy, now, now);
    }

    /** Delay before the next attempt after {@code attemptsDone} failed ones: 30 s × 2^(n−1). */
    public static Duration backoff(int attemptsDone) {
        int n = Math.max(1, Math.min(attemptsDone, 10));
        return FIRST_RETRY.multipliedBy(1L << (n - 1));
    }
}
