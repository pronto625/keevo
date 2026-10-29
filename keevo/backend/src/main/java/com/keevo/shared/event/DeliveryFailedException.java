package com.keevo.shared.event;

/**
 * DeliveryFailedException — the outbound provider (WhatsApp, push) failed to send the message of a
 * {@link DeliverOnce} listener (Story 12.4, orchestrator ruling R2).
 *
 * <p>Thrown out of the listener on purpose, past its best-effort catch blocks: the publication registry then
 * leaves the publication incomplete and the receipt is released, so the scheduled resubmission retries it.
 * Non-retryable conditions (no recipient, feature disabled, invalid phone) never throw it.
 */
public class DeliveryFailedException extends RuntimeException {

    public DeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
