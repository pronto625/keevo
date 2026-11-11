package com.keevo.messaging.whatsapp.domain.port.out;

import com.keevo.messaging.channel.domain.model.SendOutcome;

import java.util.List;

/**
 * Story 19.9b — one platform WhatsApp provider of the {@code ChainWhatsAppSender} chain (Strategy): Meta Cloud API
 * (approved templates only), Wassender and Twilio (free text), the no-op stub. Adding a provider = one adapter + its
 * place in a {@code keevo.whatsapp.provider} preset; callers of {@code WhatsAppSender} are unchanged.
 */
public interface WhatsAppProvider {

    /** Configuration name ({@code meta}, {@code wassender}, {@code twilio}, {@code noop}), lower case. */
    String name();

    /** False when its keys are missing: the chain skips it without any call. */
    boolean isConfigured();

    /**
     * Sends a free-text message. Never throws: logs its own failure at WARN (provider name and HTTP status only,
     * never the text, a key or the full phone).
     *
     * @param phone E.164 phone ({@code +} and digits)
     */
    SendOutcome sendText(String phone, String text);

    /**
     * Sends the free-text fallback of a sign-in code. Unlike {@link #sendText}, a read timeout must be
     * {@link SendOutcome#UNKNOWN}: the chain then stops instead of sending a second code elsewhere.
     */
    default SendOutcome sendCodeText(String phone, String text) {
        return sendText(phone, text);
    }

    /** True when the provider sends sign-in codes through an approved template ({@link #sendCode}). */
    default boolean supportsTemplates() {
        return false;
    }

    /**
     * Sends a sign-in code through the provider's approved template. Never throws.
     *
     * @param language {@code fr} or {@code en}
     * @return {@link SendOutcome#UNKNOWN} when the request went out without an answer (read timeout): the chain then
     *         stops, a fallback could deliver a second code
     */
    default SendOutcome sendCode(String phone, String code, String language) {
        return SendOutcome.FAILED;
    }

    /**
     * Story 19.9c — sends an approved platform template with its body parameters (already fitted to Meta's rules).
     * Never throws.
     *
     * @param template   Meta template name
     * @param language   Meta language code
     * @param parameters body variable values in order (empty: no body component)
     * @return {@link SendOutcome#UNKNOWN} on a read timeout (the chain then sends nothing more)
     */
    default SendOutcome sendTemplate(String phone, String template, String language, List<String> parameters) {
        return SendOutcome.FAILED;
    }
}
