package com.keevo.whatsappcloud.config;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;

/**
 * Story 21.5 — a Graph failure as the API error the owner reads: Meta refused (with its own text when it gave one) or
 * could not be reached → {@code WHATSAPP_CLOUD_META_ERROR} (502); no answer in time on a send →
 * {@code WHATSAPP_SEND_UNCERTAIN} (409, maybe delivered); on any other call the outcome is treated as a failure.
 */
public final class MetaErrors {

    static final int MAX_USER_TEXT = 300;

    private MetaErrors() {
    }

    public static DomainException of(MetaGraphException e, boolean send) {
        if (send && e.kind() == MetaGraphException.Kind.UNKNOWN) {
            return new DomainException(ErrorCode.WHATSAPP_SEND_UNCERTAIN, e.getMessage());
        }
        String user = e.userMessage();
        if (e.kind() == MetaGraphException.Kind.REFUSED && user != null && !user.isBlank()) {
            String text = user.strip();
            if (text.length() > MAX_USER_TEXT) {
                text = text.substring(0, MAX_USER_TEXT) + "…";
            }
            return DomainException.userFacing(ErrorCode.WHATSAPP_CLOUD_META_ERROR, "WhatsApp a refusé la demande : " + text);
        }
        return new DomainException(ErrorCode.WHATSAPP_CLOUD_META_ERROR, e.getMessage());
    }

    /** Meta's refusal, for a short French note stored on a template ({@code submitError}). */
    public static String note(MetaGraphException e) {
        String user = e.userMessage();
        if (user != null && !user.isBlank()) {
            String text = user.strip();
            return text.length() > 480 ? text.substring(0, 480) + "…" : text;
        }
        return switch (e.kind()) {
            case REFUSED -> "WhatsApp a refusé ce modèle";
            case UNAVAILABLE, UNKNOWN -> "WhatsApp ne répond pas : le modèle sera soumis à nouveau plus tard";
        };
    }
}
