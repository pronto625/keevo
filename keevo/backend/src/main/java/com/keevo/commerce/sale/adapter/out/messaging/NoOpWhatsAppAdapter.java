package com.keevo.commerce.sale.adapter.out.messaging;

import com.keevo.commerce.sale.domain.port.out.WhatsAppPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NoOpWhatsAppAdapter — Stub implementation of WhatsAppPort for MVP.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Logs the message instead of sending it. Will be replaced by a real
 * WhatsApp Business API integration post-MVP.
 */
@Component
public class NoOpWhatsAppAdapter implements WhatsAppPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpWhatsAppAdapter.class);

    @Override
    public void sendReport(String phoneNumber, String message) {
        log.info("[WhatsApp Stub] Would send to {}: \n{}", phoneNumber, message);
    }
}
