package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanKind;

import java.time.Instant;
import java.time.LocalDate;
import static com.keevo.shared.domain.time.KeevoZones.WAT;
import java.time.format.DateTimeFormatter;

/**
 * PlanWhatsAppMessages — owner-facing WhatsApp texts of the plan lifecycle (Story 9-2). The account
 * suspension / reactivation texts belong to admin since 12.2d.
 * Dates are rendered {@code dd/MM/yyyy} in WAT ({@code Africa/Douala}).
 */
public final class PlanWhatsAppMessages {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final String DOWNGRADED =
            "Votre abonnement Keevo est repassé au plan Gratuit. Vos données sont conservées.";
    static final String TRIAL_EXPIRED =
            "Votre essai Keevo est terminé : votre compte est passé au plan Gratuit. Vos données sont conservées.";

    private PlanWhatsAppMessages() {}

    /**
     * Story 19.7b — « Keevo Team », « Keevo Pro »… from the plan's catalogue label; a missing label (unknown plan,
     * event published before 19.7b) gives the neutral « Keevo ».
     */
    static String offer(String planLabel) {
        return planLabel == null || planLabel.isBlank() ? "Keevo" : "Keevo " + planLabel.strip();
    }

    /** @param planLabel catalogue label of the granted plan, {@code null} → neutral text */
    public static String activated(String planLabel, Instant expiresAt) {
        return "🎉 Votre abonnement " + offer(planLabel) + " est activé jusqu'au " + format(expiresAt)
                + ". Profitez de toutes les fonctionnalités !";
    }

    public static String downgraded() {
        return DOWNGRADED;
    }

    /**
     * Expiry text by the expired plan's kind: a trial end is not an expired paid subscription.
     *
     * @param planLabel catalogue label of the expired paid plan, {@code null} → neutral text
     */
    public static String expired(PlanKind previousKind, String planLabel) {
        return previousKind == PlanKind.TRIAL ? TRIAL_EXPIRED
                : "Votre abonnement " + offer(planLabel) + " a expiré : votre compte est passé au plan Gratuit. "
                        + "Vos données sont conservées.";
    }

    /** @param planLabel catalogue label of the paid plan, {@code null} → neutral text */
    public static String reminder(String planLabel, LocalDate expiryDate) {
        return "⏰ Votre abonnement " + offer(planLabel) + " expire le " + DATE.format(expiryDate)
                + ". Contactez-nous pour renouveler.";
    }

    public static String trialReminder(LocalDate expiryDate) {
        return "⏰ Votre essai Keevo se termine le " + DATE.format(expiryDate)
                + ". Contactez-nous pour choisir une offre et garder toutes les fonctionnalités.";
    }

    static String format(Instant instant) {
        return DATE.format(instant.atZone(WAT).toLocalDate());
    }
}
