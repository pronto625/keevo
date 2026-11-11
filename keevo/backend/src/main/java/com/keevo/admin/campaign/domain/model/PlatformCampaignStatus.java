package com.keevo.admin.campaign.domain.model;

/** Story 21.6 — the life of a platform campaign (same as Bref): DRAFT, SCHEDULED, SENDING, SENT, CANCELLED. */
public enum PlatformCampaignStatus {
    DRAFT("Brouillon"),
    SCHEDULED("Programmée"),
    SENDING("En cours d'envoi"),
    SENT("Envoyée"),
    CANCELLED("Annulée");

    private final String label;

    PlatformCampaignStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean editable() {
        return this == DRAFT || this == SCHEDULED;
    }
}
