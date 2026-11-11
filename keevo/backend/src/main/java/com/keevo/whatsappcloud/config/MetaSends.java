package com.keevo.whatsappcloud.config;

import com.keevo.shared.capability.notification.PlatformWhatsApp.SendResult;
import com.keevo.shared.capability.notification.PlatformWhatsApp.SendResult.Outcome;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Story 21.6 — one message sent to Meta in a campaign, as an outcome (never an exception): SENT with Meta's id;
 * RATE_LIMITED when Meta refused it for throughput / spam / pair limits (codes {@link #RATE_LIMIT_CODES}: not sent, the
 * campaign pauses and retries it); FAILED when Meta refused it otherwise or could not be reached (certainly not sent,
 * with a French reason ≤ 200 characters); UNCERTAIN when the request went out without an answer (maybe delivered,
 * never resent).
 */
public final class MetaSends {

    /** Meta's rate-limit codes: app (4, 80007), throughput (130429), spam rate (131048), pair rate (131056). */
    public static final Set<Integer> RATE_LIMIT_CODES = Set.of(4, 80007, 130429, 131048, 131056);
    
    private MetaSends() {
    }

    public static SendResult send(Supplier<String> call) {
        try {
            return SendResult.sent(call.get());
        } catch (MetaGraphException e) {
            return outcome(e);
        }
    }

    public static SendResult outcome(MetaGraphException e) {
        return switch (e.kind()) {
            case UNKNOWN -> SendResult.of(Outcome.UNCERTAIN, "WhatsApp n'a pas répondu à temps : message peut-être reçu");
            case UNAVAILABLE -> SendResult.of(Outcome.FAILED, "WhatsApp injoignable");
            case REFUSED -> RATE_LIMIT_CODES.contains(e.metaCode())
                    ? SendResult.of(Outcome.RATE_LIMITED, "Limite d'envoi de WhatsApp atteinte")
                    : SendResult.of(Outcome.FAILED, reason(e.metaCode()));
        };
    }

    /** A French reason by Meta's code (Meta's own text may be in English: never shown as is). */
    static String reason(int metaCode) {
        return switch (metaCode) {
            case 131026 -> "Ce numéro n'utilise pas WhatsApp";
            case 131047 -> "Fenêtre de 24 heures dépassée";
            case 132000, 132001, 132005, 132007, 132012 -> "Modèle refusé par WhatsApp (variables ou langue)";
            case 132015, 132016 -> "Modèle suspendu ou désactivé par WhatsApp";
            case 131051 -> "Type de message non pris en charge";
            default -> "Refusé par WhatsApp";
        };
    }
}
