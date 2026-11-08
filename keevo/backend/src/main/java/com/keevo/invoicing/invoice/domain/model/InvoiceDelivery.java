package com.keevo.invoicing.invoice.domain.model;

import java.time.Instant;

/**
 * Sendings of an invoice (Story 14.2): the last one (when, to which recipient) and how many times it was sent.
 * {@link #NONE} for an invoice never sent.
 *
 * <p>Story 20.8: {@code lastSentChannel} = the channel of the last delivered sending; {@code lastSentTo} is an E.164
 * number (WhatsApp, SMS) or an email address. A sending recorded before 20.8 has no channel: it was a WhatsApp one
 * ({@link #channel()}). {@code lastSendStatus} / {@code lastSendStatusChannel} / {@code lastSendErrorCode} = where the
 * last queued request stands (PENDING, SENT, FAILED, UNKNOWN) — how the app learns the outcome of an offline sending
 * through the pull; {@code null} when no request was queued.
 */
public record InvoiceDelivery(Instant lastSentAt, String lastSentTo, int sendCount, SendChannel lastSentChannel,
                              SendStatus lastSendStatus, SendChannel lastSendStatusChannel, String lastSendErrorCode) {

    public static final InvoiceDelivery NONE = new InvoiceDelivery(null, null, 0, null, null, null, null);

    /** Story 14.2 shape: no channel recorded. */
    public InvoiceDelivery(Instant lastSentAt, String lastSentTo, int sendCount) {
        this(lastSentAt, lastSentTo, sendCount, null, null, null, null);
    }

    /** Story 20.8 shape before the outbox status. */
    public InvoiceDelivery(Instant lastSentAt, String lastSentTo, int sendCount, SendChannel lastSentChannel) {
        this(lastSentAt, lastSentTo, sendCount, lastSentChannel, null, null, null);
    }

    /** One more WhatsApp sending to {@code phone} at {@code now}. */
    public InvoiceDelivery sentTo(String phone, Instant now) {
        return sentTo(phone, SendChannel.WHATSAPP, now);
    }

    /** Story 20.8 — one more sending to {@code recipient} by {@code channel} at {@code now} (last status SENT). */
    public InvoiceDelivery sentTo(String recipient, SendChannel channel, Instant now) {
        return new InvoiceDelivery(now, recipient, sendCount + 1, channel, SendStatus.SENT, channel, null);
    }

    /** The channel of the last sending: the recorded one, WhatsApp for an older sending, {@code null} if never sent. */
    public SendChannel channel() {
        if (lastSentChannel != null) {
            return lastSentChannel;
        }
        return sendCount > 0 || lastSentAt != null ? SendChannel.WHATSAPP : null;
    }
}
