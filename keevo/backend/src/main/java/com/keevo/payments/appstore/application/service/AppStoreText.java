package com.keevo.payments.appstore.application.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static com.keevo.shared.domain.time.KeevoZones.WAT;

/** Story 21.2 — the French texts of the App Store purchases (never a code or an id). */
final class AppStoreText {

    static final String UNAVAILABLE = "Les achats App Store sont momentanément indisponibles";
    static final String PROVIDER_FAILED = "La vérification de l'achat App Store a échoué. Réessayez.";
    static final String NOTHING_ACTIVE = "Aucun abonnement App Store actif n'a été trouvé pour ce compte";
    static final String NOT_OFFERED = "Cet abonnement App Store ne correspond à aucune offre. Contactez le support.";
    static final String BOUND_ELSEWHERE = "Cet abonnement App Store est déjà rattaché à une autre entreprise";
    static final String COMPANY_NOT_FOUND = "Entreprise introuvable";
    static final String LEDGER_NOTE = "Paiement en ligne — App Store";
    static final String REFUND_REASON = "Remboursé par Apple";
    static final String EXPIRY_REASON = "Abonnement App Store terminé";
    static final String REFUND_PLAN_REASON = "Abonnement App Store remboursé";
    static final String TRANSFER_REASON = "Abonnement App Store transféré vers un autre compte";
    static final String SOURCE = "App Store";

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(WAT);

    private AppStoreText() {
    }

    static String date(Instant at) {
        return FR_DATE.format(at);
    }

    /** The message of the owner view for a subscription state. */
    static String message(com.keevo.payments.appstore.domain.model.SubscriptionStatus status, String planLabel,
                          Instant expiresAt, Instant graceExpiresAt) {
        return switch (status) {
            case ACTIVE -> expiresAt == null ? "Votre plan " + planLabel + " est actif"
                    : "Votre plan " + planLabel + " est actif et se renouvelle le " + date(expiresAt);
            case CANCELLED -> expiresAt == null ? "Le renouvellement automatique est désactivé"
                    : "Le renouvellement automatique est désactivé : votre plan " + planLabel
                    + " reste actif jusqu'au " + date(expiresAt);
            case BILLING_ISSUE -> graceExpiresAt == null
                    ? "Apple n'a pas pu renouveler votre abonnement : mettez à jour votre moyen de paiement"
                    : "Apple n'a pas pu renouveler votre abonnement : mettez à jour votre moyen de paiement avant le "
                    + date(graceExpiresAt);
            case EXPIRED -> "Votre abonnement App Store est terminé";
            case REVOKED -> "Votre abonnement App Store a été remboursé";
            case TRANSFERRED -> "Votre abonnement App Store a été transféré vers un autre compte";
        };
    }
}
