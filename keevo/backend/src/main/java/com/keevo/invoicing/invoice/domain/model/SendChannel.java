package com.keevo.invoicing.invoice.domain.model;

import java.util.Locale;

/**
 * Story 20.8 (FUS-FR22) — how a document is sent to its client: WhatsApp (Story 14.2, the default), SMS or email. The
 * code is the API / stored value ({@code invoices.last_sent_channel}), the label is what people read.
 */
public enum SendChannel {
    WHATSAPP("WhatsApp"),
    SMS("SMS"),
    EMAIL("E-mail");

    private final String label;

    SendChannel(String label) {
        this.label = label;
    }

    /** French label shown to the user. */
    public String label() {
        return label;
    }

    /**
     * The channel named by the API ({@code null} / blank = {@link #WHATSAPP}, the 14.2 behaviour), case-insensitive.
     *
     * @throws IllegalArgumentException an unknown name
     */
    public static SendChannel parse(String value) {
        if (value == null || value.isBlank()) {
            return WHATSAPP;
        }
        return SendChannel.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    /** The stored value ({@code null} = never sent, or sent before Story 20.8). */
    public static SendChannel fromStored(String value) {
        return value == null ? null : SendChannel.valueOf(value);
    }
}
