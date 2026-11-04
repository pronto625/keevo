package com.keevo.messaging.whatsapp.application;

import com.keevo.messaging.channel.domain.model.Recipients;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.whatsapp.domain.exception.WhatsAppDeliveryException;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import com.keevo.shared.capability.notification.WhatsAppSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Story 19.9b — the platform {@link WhatsAppSender}: a chain of responsibility over the {@link WhatsAppProvider}s of
 * the {@code keevo.whatsapp.provider} preset (built once by {@code WhatsAppChainConfig}), mirroring 19.9's SMS / email
 * failover.
 *
 * <ul>
 *   <li>{@link #sendReport} (free text): only the text providers (Wassender then Twilio), never a template provider —
 *       Meta free text only works inside the 24-hour customer window. First acceptance wins; a provider without keys
 *       is skipped; every provider failing throws {@link WhatsAppDeliveryException} (as before).</li>
 *   <li>{@link #sendCode} (sign-in / reset code): the template providers first (Meta, approved template
 *       {@code bref_point_otp}); on a certain failure (HTTP error, missing keys) the fallback text goes through the
 *       text providers. An unknown outcome (read timeout) stops the chain: no fallback, no second code.</li>
 * </ul>
 *
 * <p>Logs carry the provider name and the phone masked to its last 3 digits; never a code, a text or a key.
 * Complexity per code: at most 2 template calls (fr then en on a missing translation) + one call per text provider.
 */
public class ChainWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(ChainWhatsAppSender.class);

    /** Name of the logging stub provider: never counts as a configured WhatsApp integration. */
    public static final String STUB = "noop";

    private final List<WhatsAppProvider> templateProviders;
    private final List<WhatsAppProvider> textProviders;
    private final boolean configured;
    private final boolean codesConfigured;

    public ChainWhatsAppSender(List<WhatsAppProvider> providers) {
        List<WhatsAppProvider> all = List.copyOf(providers);
        this.templateProviders = all.stream().filter(WhatsAppProvider::supportsTemplates).toList();
        this.textProviders = all.stream().filter(p -> !p.supportsTemplates()).toList();
        this.configured = textProviders.stream().anyMatch(p -> !STUB.equals(p.name()) && p.isConfigured());
        this.codesConfigured = all.stream().anyMatch(p -> !STUB.equals(p.name()) && p.isConfigured());
        all.forEach(p -> log.info("[WhatsApp] provider {}: {}", p.name(),
                STUB.equals(p.name()) ? "stub (nothing sent)" : p.isConfigured() ? "configured" : "MISSING keys"));
    }

    /** A real (non-stub) free-text provider has its keys: what every {@link #sendReport} caller needs. */
    @Override
    public boolean isConfigured() {
        return configured;
    }

    /** A real provider (template or free text) has its keys: a sign-in code can go out. */
    @Override
    public boolean canSendCodes() {
        return codesConfigured;
    }

    @Override
    public void sendReport(String phoneNumber, String reportText) {
        sendText(sanitizePhone(phoneNumber), reportText, false);
    }

    @Override
    public void sendCode(String phoneNumber, String code, String fallbackText, String language) {
        String phone = sanitizePhone(phoneNumber);
        String lang = language(language);
        for (WhatsAppProvider provider : templateProviders) {
            if (!provider.isConfigured()) {
                log.warn("[WhatsApp] {} skipped for a code — keys not configured", provider.name());
                continue;
            }
            SendOutcome outcome;
            try {
                outcome = provider.sendCode(phone, code, lang);
            } catch (RuntimeException e) {
                log.warn("[WhatsApp] {} code failed ({})", provider.name(), e.getClass().getSimpleName());
                continue;
            }
            if (outcome == SendOutcome.DELIVERED) {
                log.info("[WhatsApp] code sent to {} via {} template", Recipients.maskPhone(phone), provider.name());
                return;
            }
            if (outcome == SendOutcome.UNKNOWN) {
                log.warn("[WhatsApp] {} code outcome unknown for {} — no fallback (no second code)",
                        provider.name(), Recipients.maskPhone(phone));
                return;
            }
        }
        if (!textProviders.isEmpty()) {
            log.info("[WhatsApp] code to {} — template not delivered, fallback text", Recipients.maskPhone(phone));
        }
        sendText(phone, fallbackText, true);
    }

    /** {@code code}: the fallback of a code, where a read timeout stops the chain (no second code). */
    private void sendText(String phone, String text, boolean code) {
        for (WhatsAppProvider provider : textProviders) {
            if (!provider.isConfigured()) {
                log.warn("[WhatsApp] {} skipped — keys not configured", provider.name());
                continue;
            }
            SendOutcome outcome;
            try {
                outcome = code ? provider.sendCodeText(phone, text) : provider.sendText(phone, text);
            } catch (RuntimeException e) {
                log.warn("[WhatsApp] {} failed ({})", provider.name(), e.getClass().getSimpleName());
                continue;
            }
            if (outcome == SendOutcome.DELIVERED) {
                log.debug("[WhatsApp] sent to {} via {}", Recipients.maskPhone(phone), provider.name());
                return;
            }
            if (outcome == SendOutcome.UNKNOWN) {
                log.warn("[WhatsApp] {} outcome unknown for {} — not retried elsewhere", provider.name(),
                        Recipients.maskPhone(phone));
                return;
            }
        }
        throw new WhatsAppDeliveryException("WhatsApp message not delivered to " + Recipients.maskPhone(phone)
                + " — tried: " + String.join(", ", textProviders.stream().map(WhatsAppProvider::name).toList()));
    }

    /** {@code fr} unless the language says English (Keevo's default language is French). */
    static String language(String language) {
        return language != null && language.strip().toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "fr";
    }

    /** Spaces, dashes and parentheses stripped, {@code +} prefixed (as the former adapters). */
    static String sanitizePhone(String phone) {
        String cleaned = phone == null ? "" : phone.replaceAll("[\\s\\-()]", "");
        return cleaned.startsWith("+") ? cleaned : "+" + cleaned;
    }
}
