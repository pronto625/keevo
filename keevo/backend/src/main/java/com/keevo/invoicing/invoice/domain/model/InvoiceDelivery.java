package com.keevo.invoicing.invoice.domain.model;

import java.time.Instant;

/**
 * WhatsApp sendings of an invoice (Story 14.2): the last one (when, to which E.164 number) and how many times it was
 * sent. {@link #NONE} for an invoice never sent.
 */
public record InvoiceDelivery(Instant lastSentAt, String lastSentTo, int sendCount) {

    public static final InvoiceDelivery NONE = new InvoiceDelivery(null, null, 0);

    /** One more sending to {@code phone} at {@code now}. */
    public InvoiceDelivery sentTo(String phone, Instant now) {
        return new InvoiceDelivery(now, phone, sendCount + 1);
    }
}
