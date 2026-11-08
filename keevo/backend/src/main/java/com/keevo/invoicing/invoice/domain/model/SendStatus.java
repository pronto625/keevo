package com.keevo.invoicing.invoice.domain.model;

/**
 * Story 20.8 — where a request to send a document stands ({@code invoice_send_requests.status}, and the last one on
 * {@code invoices.last_send_status}).
 * <ul>
 *   <li>{@link #PENDING} — queued (offline {@code SEND_INVOICE}), waiting for the outbox (after commit or the poller);</li>
 *   <li>{@link #SENDING} — claimed by one worker (row-level transition {@code PENDING → SENDING}); never shown;</li>
 *   <li>{@link #SENT} — a provider accepted the message;</li>
 *   <li>{@link #FAILED} — certainly not sent (refused, or 5 attempts failed);</li>
 *   <li>{@link #UNKNOWN} — the provider did not answer: maybe delivered, never retried automatically.</li>
 * </ul>
 */
public enum SendStatus {
    PENDING("En attente d'envoi"),
    SENDING("Envoi en cours"),
    SENT("Envoyé"),
    FAILED("Échec de l'envoi"),
    UNKNOWN("Envoi incertain");

    private final String label;

    SendStatus(String label) {
        this.label = label;
    }

    /** French label shown to the user. */
    public String label() {
        return label;
    }

    /** The stored value ({@code null} = none). */
    public static SendStatus fromStored(String value) {
        return value == null ? null : SendStatus.valueOf(value);
    }
}
