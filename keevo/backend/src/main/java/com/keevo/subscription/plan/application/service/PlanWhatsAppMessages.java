package com.keevo.subscription.plan.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * PlanWhatsAppMessages — owner-facing WhatsApp texts of the plan lifecycle (Story 9-2).
 * Dates are rendered {@code dd/MM/yyyy} in WAT ({@code Africa/Douala}).
 */
public final class PlanWhatsAppMessages {

    public static final ZoneId WAT = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final String DOWNGRADED =
            "Votre abonnement Keevo est repassé au plan Gratuit. Vos données sont conservées.";
    static final String EXPIRED =
            "Votre abonnement Keevo Premium a expiré : votre compte est passé au plan Gratuit. "
            + "Vos données sont conservées.";

    private PlanWhatsAppMessages() {}

    public static String activated(Instant expiresAt) {
        return "🎉 Votre compte Keevo Premium est activé jusqu'au " + format(expiresAt)
                + ". Profitez de toutes les fonctionnalités !";
    }

    public static String downgraded() {
        return DOWNGRADED;
    }

    public static String expired() {
        return EXPIRED;
    }

    public static String reminder(LocalDate expiryDate) {
        return "⏰ Votre abonnement Keevo Premium expire le " + DATE.format(expiryDate)
                + ". Contactez-nous pour renouveler.";
    }

    public static String trialReminder(LocalDate expiryDate) {
        return "⏰ Votre essai Keevo Premium se termine le " + DATE.format(expiryDate)
                + ". Contactez-nous pour passer au Premium et garder toutes les fonctionnalités.";
    }

    static String format(Instant instant) {
        return DATE.format(instant.atZone(WAT).toLocalDate());
    }
}
