package com.keevo.shared.capability.notification;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.6 — the platform WhatsApp number (Bref « Bref Point », 19.9b) for the super-admin's platform campaigns:
 * approved templates sent through Meta's Cloud API (the message is kept in the platform inbox with its campaign, so
 * Meta's delivery statuses come back), and the numbers that answered STOP to it. Implemented by the
 * {@code whatsapp-cloud} module; consumed by {@code admin} (platform campaigns), which never reads its tables.
 */
public interface PlatformWhatsApp {

    /** False when the platform number (id + token) is not configured: nothing can be sent from it. */
    boolean isConfigured();

    /**
     * Sends an approved template of the platform number to {@code to}. Never throws for Meta's answer.
     *
     * @param to         Meta's format: digits only, country code included
     * @param campaignId the platform campaign the message belongs to ({@code null} = none)
     */
    SendResult sendTemplate(String to, TemplateMessage message, UUID campaignId);

    /** The numbers among {@code phones} (digits) that answered STOP to the platform number. One query. */
    Set<String> optedOut(Collection<String> phones);

    /**
     * An approved template with its values: {@code bodyText} is the template body as approved ({@code {{1}}} or
     * {@code {{name}}} variables, sent in that order with their names when named), {@code values} one per variable,
     * {@code headerFormat} NONE | TEXT | IMAGE | VIDEO | DOCUMENT, {@code header} the media of a media header.
     */
    record TemplateMessage(String name, String language, String bodyText, List<String> values, String headerFormat,
                           HeaderMedia header) {

        public TemplateMessage {
            values = values == null ? List.of() : List.copyOf(values);
            headerFormat = headerFormat == null || headerFormat.isBlank() ? "NONE" : headerFormat;
        }
    }

    /** The media of a media header: a media id uploaded to the sending number, or a public https link. */
    record HeaderMedia(String id, String link) {
    }

    /**
     * What happened to one send: {@link Outcome#SENT} with Meta's message id; {@link Outcome#FAILED} certainly not
     * sent (with a French reason); {@link Outcome#UNCERTAIN} maybe sent (never resend); {@link Outcome#RATE_LIMITED}
     * refused by Meta's throughput limits (not sent, retry later); {@link Outcome#UNAVAILABLE} the number cannot send.
     */
    record SendResult(Outcome outcome, String waMessageId, String error) {

        public enum Outcome { SENT, FAILED, UNCERTAIN, RATE_LIMITED, UNAVAILABLE }

        public static SendResult sent(String waMessageId) {
            return new SendResult(Outcome.SENT, waMessageId, null);
        }

        public static SendResult of(Outcome outcome, String error) {
            return new SendResult(outcome, null, error);
        }
    }
}
