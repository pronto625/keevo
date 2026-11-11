package com.keevo.whatsappcloud.consent;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Story 21.6 — the STOP / START answers a contact sends to a WhatsApp number (port of Bref
 * {@code WhatsAppService.handleIncomingMessage} :623-667): the text, lower-cased, without accents and keeping letters
 * only, is one of the opt-out words (stop, arrêt, arrêter, désabonner, unsubscribe) or opt-in words (start, reprendre,
 * subscribe). Anything else is a normal message. And the confirmation texts Bref sends back (company number /
 * platform number). Pure.
 */
public final class KeywordConsent {

    public enum Answer { OPT_OUT, OPT_IN }

    static final Set<String> OPT_OUT = Set.of("stop", "arret", "arreter", "desabonner", "unsubscribe");
    static final Set<String> OPT_IN = Set.of("start", "reprendre", "subscribe");
    /** Longer texts are messages, never keywords (bounds the normalization). */
    static final int MAX_KEYWORD_TEXT = 40;

    private KeywordConsent() {
    }

    /** The answer a text carries, if it is a keyword. */
    public static Optional<Answer> parse(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_KEYWORD_TEXT) {
            return Optional.empty();
        }
        String k = Normalizer.normalize(text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z]", "");
        if (OPT_OUT.contains(k)) {
            return Optional.of(Answer.OPT_OUT);
        }
        if (OPT_IN.contains(k)) {
            return Optional.of(Answer.OPT_IN);
        }
        return Optional.empty();
    }

    /** Bref's confirmation, from the company number ({@code platform} false) or the platform number. */
    public static String confirmation(Answer answer, boolean platform) {
        if (platform) {
            return answer == Answer.OPT_OUT
                    ? "Vous ne recevrez plus nos messages promotionnels sur WhatsApp. Répondez START pour vous "
                    + "réabonner. Les codes de connexion et messages liés à votre compte continueront d'être envoyés."
                    : "Vous êtes de nouveau abonné(e) à nos messages. Répondez STOP à tout moment pour vous désabonner.";
        }
        return answer == Answer.OPT_OUT
                ? "Vous ne recevrez plus les offres de cette boutique sur WhatsApp. Répondez START pour vous réabonner."
                : "Vous êtes de nouveau abonné(e) aux offres de cette boutique. Répondez STOP à tout moment pour vous "
                + "désabonner.";
    }
}
