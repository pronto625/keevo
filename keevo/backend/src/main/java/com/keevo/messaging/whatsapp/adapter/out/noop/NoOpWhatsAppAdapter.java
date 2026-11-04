package com.keevo.messaging.whatsapp.adapter.out.noop;

import com.keevo.messaging.channel.domain.model.Recipients;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.whatsapp.application.ChainWhatsAppSender;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NoOpWhatsAppAdapter — the logging stub of dev/test ({@code keevo.whatsapp.provider=noop}, the default).
 * Story 4.4 — Created | Story 8.0 — Moved to messaging | Story 19.9b — a {@link WhatsAppProvider} of the chain.
 *
 * <p>Logs the masked phone and the text length only: never the text, which can hold a sign-in code. "Configured"
 * (it needs no key) but the chain never reports it as a real WhatsApp integration.
 */
@Component
public class NoOpWhatsAppAdapter implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(NoOpWhatsAppAdapter.class);

    @Override
    public String name() {
        return ChainWhatsAppSender.STUB;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public SendOutcome sendText(String phone, String text) {
        log.info("[WhatsApp Stub] would send {} characters to {}", text == null ? 0 : text.length(),
                Recipients.maskPhone(phone));
        return SendOutcome.DELIVERED;
    }
}
