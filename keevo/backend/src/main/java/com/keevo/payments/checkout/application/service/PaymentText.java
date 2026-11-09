package com.keevo.payments.checkout.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static com.keevo.shared.domain.time.KeevoZones.WAT;

/** Story 21.1 — the French texts of the checkout (never a code or an id). */
final class PaymentText {

    static final String OWNER_ONLY = "Seul le propriétaire peut payer l'abonnement";
    static final String METHOD_REQUIRED = "Choisissez Orange Money ou MTN Mobile Money";
    static final String PHONE_INVALID = "Numéro Mobile Money invalide (numéro camerounais à 9 chiffres)";
    static final String CODE_INVALID = "Code de validation invalide";
    static final String CODE_REFUSED = "Code de validation incorrect";
    static final String NO_CODE_EXPECTED = "Ce paiement n'attend pas de code de validation";
    static final String NOT_CANCELLABLE = "Ce paiement ne peut plus être annulé";
    static final String START_FAILED = "Le paiement n'a pas pu démarrer";
    static final String PROVIDER_FAILED = "Le paiement n'a pas abouti";
    static final String CANCELLED = "Paiement annulé";
    static final String EXPIRED = "Délai de paiement dépassé";
    static final String ENTER_CODE = "Saisissez le code reçu par SMS pour valider le paiement";
    static final String VALIDATE_ON_PHONE = "Validez le paiement sur votre téléphone";
    static final String COMPANY_NOT_FOUND = "Entreprise introuvable";
    static final String FREE_OFFER_TAKEN = "Vous avez déjà profité de cette offre";
    static final String PROMO_USED_UP = "Code promo invalide ou expiré";
    static final String TOO_MANY_CHECKOUTS = "Trop de tentatives de paiement. Réessayez dans une heure.";
    static final String PLAN_OR_PACK = "Choisissez un plan ou un pack, pas les deux";
    static final String PACK_NOT_OFFERED = "Ce pack n'est pas proposé";
    static final String PACK_USELESS =
            "Votre offre ne limite pas cette consommation : ce pack ne vous servirait pas";
    static final String PACK_NO_PROMO = "Les codes promo ne s'appliquent pas aux packs";
    static final String PAYMENT_RECEIVED = "Paiement reçu";
    static final String PACK_CREDITED = "Pack crédité";

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(WAT);

    private PaymentText() {
    }

    static String dialThenValidate(String ussd) {
        return "Composez " + ussd + " puis validez le paiement sur votre téléphone";
    }

    static String activeUntil(String planLabel, Instant expiresAt) {
        return "Votre plan " + planLabel + " est actif jusqu'au " + FR_DATE.format(expiresAt);
    }

    /** « 100 SMS ajoutés à votre compte » */
    static String packAdded(com.keevo.payments.checkout.domain.model.PaymentRequest r) {
        return r.packQuantity() + " " + r.packMetric().label()
                + (r.packQuantity() > 1 ? " ajoutés à votre compte" : " ajouté à votre compte");
    }

    /** « 100 SMS ajoutés à votre compte. » (notification) */
    static String packCredited(com.keevo.payments.checkout.domain.model.PaymentRequest r) {
        return packAdded(r) + ".";
    }

    /** Reason of a late payment added to the credit (« Paiement reçu après l'annulation : 2 500 FCFA … »). */
    static String creditedLate(com.keevo.payments.checkout.domain.model.PaymentStatus status, long amountXaf) {
        String after = switch (status) {
            case CANCELLED -> "après l'annulation";
            case EXPIRED -> "après l'expiration du délai";
            default -> "après l'échec du paiement";
        };
        return "Paiement reçu " + after + " : " + xaf(amountXaf) + " ajoutés à votre crédit";
    }

    /** {@code 3 500 FCFA}. */
    static String xaf(long amount) {
        return com.keevo.payments.credit.application.service.AccountCreditService.xaf(amount);
    }

    static String date(Instant at) {
        return FR_DATE.format(at);
    }

    static DomainException invalid(String field, String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }

    static DomainException conflict(String message) {
        return DomainException.userFacing(ErrorCode.INVALID_STATE, message);
    }
}
