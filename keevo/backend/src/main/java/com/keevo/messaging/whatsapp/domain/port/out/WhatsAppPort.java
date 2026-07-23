package com.keevo.messaging.whatsapp.domain.port.out;

/**
 * WhatsAppPort — Port out for sending WhatsApp messages.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 * Story 8.0 — Moved from commerce.sale.domain.port.out to messaging.whatsapp.domain.port.out
 *
 * <p>MVP: NoOpWhatsAppAdapter logs the message (no real sending).
 * Production: WassenderWhatsAppAdapter sends via Wassender API.
 */
public interface WhatsAppPort {

    /**
     * Returns true if a real WhatsApp integration is configured.
     * The NoOp stub returns false.
     */
    default boolean isConfigured() { return true; }

    /**
     * Send a report message to the given phone number.
     *
     * @param phoneNumber Recipient phone (E.164 format, e.g., +243...)
     * @param reportText  Message content (WhatsApp formatted)
     */
    void sendReport(String phoneNumber, String reportText);

    /**
     * Story 14.12 — Send an OTP (one-time password) for password reset.
     *
     * <p>Default implementation delegates to {@link #sendReport(String, String)}
     * so all existing adapters work without modification (D5).
     *
     * @param phoneNumber Recipient phone (E.164 format)
     * @param code        6-digit OTP code
     */
    default void sendOtp(String phoneNumber, String code) {
        sendReport(phoneNumber,
                "Votre code de réinitialisation Keevo est " + code
                + ". Il expire dans 10 minutes."
                + " Si vous n'avez pas demandé, ignorez ce message.");
    }
}
