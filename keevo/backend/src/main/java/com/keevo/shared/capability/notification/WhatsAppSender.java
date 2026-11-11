package com.keevo.shared.capability.notification;

/**
 * WhatsAppSender — Port out for sending WhatsApp messages.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 * Story 8.0 — Moved from commerce.sale.domain.port.out to messaging.whatsapp.domain.port.out
 * Story 12.2e — Published in messaging.api (implementations stay internal)
 * Story 12.6a — Core capability (formerly messaging.api.WhatsAppPort), implemented by messaging
 *
 * <p>Story 19.9b — implemented by messaging's {@code ChainWhatsAppSender} over the providers of the
 * {@code keevo.whatsapp.provider} preset (noop stub, Wassender, Twilio, Meta Cloud API).
 */
public interface WhatsAppSender {

    /**
     * Returns true if a real WhatsApp integration is configured.
     * The NoOp stub returns false.
     *
     * <p>v1s-15-9: abstract on purpose — a new adapter must state explicitly whether it can
     * really send, instead of silently inheriting {@code true}.
     */
    boolean isConfigured();

    /**
     * Send a report message to the given phone number.
     *
     * @param phoneNumber Recipient phone (E.164 format, e.g., +243...)
     * @param reportText  Message content (WhatsApp formatted)
     */
    void sendReport(String phoneNumber, String reportText);

    /**
     * Story 19.9b — true when a sign-in code can really go out (a template provider such as Meta, or a free-text
     * one). {@link #isConfigured} only covers free text ({@link #sendReport}). Defaults to {@link #isConfigured}.
     */
    default boolean canSendCodes() {
        return isConfigured();
    }

    /**
     * Story 19.9b — sends a sign-in or password-reset code. The platform sender tries an approved template first
     * (Meta {@code bref_point_otp}: code as body and URL button parameter, French then English on a missing
     * translation); on a certain failure it sends {@code fallbackText} as free text (Wassender then Twilio). An
     * unknown template outcome (timeout) sends nothing more: no second code. Logs never carry the code.
     *
     * <p>The default sends {@code fallbackText} through {@link #sendReport} (test doubles).
     *
     * @param phoneNumber  recipient phone (E.164)
     * @param code         the code, as the template parameter
     * @param fallbackText complete localized text holding the code, sent when no template provider delivers it
     * @param language     {@code fr} or {@code en} (anything else not starting with {@code en} is French)
     */
    default void sendCode(String phoneNumber, String code, String fallbackText, String language) {
        sendReport(phoneNumber, fallbackText);
    }

    /**
     * Story 19.9c — sends a platform message (report, alert, reminder, notice…). The platform sender uses the Meta
     * template of the message's kind when that template is declared approved and a template provider is configured;
     * otherwise, and on a certain template failure, it sends {@link PlatformWhatsAppMessage#text()} as free text
     * (Wassender then Twilio), exactly as {@link #sendReport}. An unknown template outcome (timeout) sends nothing
     * more. Failure contract = {@link #sendReport}'s (a runtime exception when nothing could be sent).
     *
     * <p>The default sends the free text through {@link #sendReport} (test doubles).
     */
    default void sendNotification(String phoneNumber, PlatformWhatsAppMessage message) {
        sendReport(phoneNumber, message.text());
    }
}
