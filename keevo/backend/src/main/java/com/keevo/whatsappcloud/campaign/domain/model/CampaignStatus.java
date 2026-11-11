package com.keevo.whatsappcloud.campaign.domain.model;

/**
 * Story 21.6 — the life of a campaign (Bref {@code CustomerCampaignStatus}): DRAFT (editable), SCHEDULED (editable,
 * sent at {@code scheduledAt}), SENDING (audience frozen, messages leaving), SENT (every recipient processed),
 * CANCELLED (a scheduled campaign stopped). French labels for the screens.
 */
public enum CampaignStatus {
    DRAFT("Brouillon"),
    SCHEDULED("Programmée"),
    SENDING("En cours d'envoi"),
    SENT("Envoyée"),
    CANCELLED("Annulée");

    private final String label;

    CampaignStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** DRAFT and SCHEDULED campaigns can still be changed, sent or deleted. */
    public boolean editable() {
        return this == DRAFT || this == SCHEDULED;
    }
}
