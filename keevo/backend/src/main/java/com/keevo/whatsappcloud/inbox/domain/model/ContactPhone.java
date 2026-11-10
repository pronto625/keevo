package com.keevo.whatsappcloud.inbox.domain.model;

import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;

/**
 * Story 21.5 — a contact's WhatsApp number in Meta's format: digits only, country code included (Bref
 * {@code toWaNumber}). 8 to 15 digits (E.164); anything else is refused in French.
 */
public final class ContactPhone {

    private ContactPhone() {
    }

    /** Digits of {@code raw}, or {@code null} when not a plausible international number. */
    public static String digitsOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        return digits.length() >= 8 && digits.length() <= 15 ? digits : null;
    }

    /** @throws InvalidRequestParameterException 400 in French */
    public static String require(String raw) {
        String digits = digitsOrNull(raw);
        if (digits == null) {
            throw new InvalidRequestParameterException("phone", "not an international number",
                    "Numéro WhatsApp invalide : indiquez le numéro avec l'indicatif du pays");
        }
        return digits;
    }
}
