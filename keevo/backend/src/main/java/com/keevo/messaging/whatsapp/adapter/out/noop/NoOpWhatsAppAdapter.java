package com.keevo.messaging.whatsapp.adapter.out.noop;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NoOpWhatsAppAdapter — Stub implementation of WhatsAppPort for dev/test.
 * Story 4.4 — Created | Story 8.0 — Moved to messaging domain, conditional loading.
 *
 * <p>Active when keevo.whatsapp.provider is "noop" or not set (matchIfMissing=true).
 * Logs the message instead of sending it.
 */
@Component
@ConditionalOnProperty(name = "keevo.whatsapp.provider", havingValue = "noop", matchIfMissing = true)
public class NoOpWhatsAppAdapter implements WhatsAppPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpWhatsAppAdapter.class);

    @Override
    public boolean isConfigured() { return false; }

    @Override
    public void sendReport(String phoneNumber, String message) {
        log.info("[WhatsApp Stub] Would send to {}: \n{}", phoneNumber, message);
    }
}
