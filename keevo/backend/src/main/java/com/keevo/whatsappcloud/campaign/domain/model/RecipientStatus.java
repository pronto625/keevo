package com.keevo.whatsappcloud.campaign.domain.model;

/**
 * Story 21.6 — one recipient of a campaign: PENDING (waiting for its batch), SENDING (claimed, the call to Meta in
 * progress), SENT (Meta accepted it; delivered / read then come from Meta's statuses), FAILED (certainly not sent),
 * SKIPPED (not sent on purpose: consent withdrawn, quota reached), UNCERTAIN (maybe sent: never resent).
 */
public enum RecipientStatus {
    PENDING("En attente"),
    SENDING("En cours d'envoi"),
    SENT("Envoyé"),
    FAILED("Échec"),
    SKIPPED("Non envoyé"),
    UNCERTAIN("Envoi incertain");

    private final String label;

    RecipientStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** French label of a recipient with Meta's delivery status of its message ({@code DELIVERED}, {@code READ}…). */
    public static String label(String recipientStatus, String deliveryStatus) {
        if ("SENT".equals(recipientStatus) && deliveryStatus != null) {
            return switch (deliveryStatus) {
                case "READ" -> "Lu";
                case "DELIVERED" -> "Délivré";
                case "FAILED" -> "Échec";
                default -> SENT.label;
            };
        }
        try {
            return valueOf(recipientStatus).label;
        } catch (RuntimeException e) {
            return PENDING.label;
        }
    }
}
