package com.keevo.commerce.sale.domain.port.out;

/**
 * WhatsAppPort — Port out for sending WhatsApp messages.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>MVP: NoOpWhatsAppAdapter logs the message (no real sending).
 * Production: implement with WhatsApp Business API.
 */
public interface WhatsAppPort {

    /**
     * Send a report message to the given phone number.
     *
     * @param phoneNumber Recipient phone (E.164 format, e.g., +243...)
     * @param reportText  Message content (WhatsApp formatted)
     */
    void sendReport(String phoneNumber, String reportText);
}
