package com.keevo.admin.campaign.domain.model;

/** Story 21.6 — the channels of a platform campaign (Bref {@code CampaignType} without {@code in_app}: a push is also
 *  kept in the app's notification inbox), with their French label. */
public enum Channel {
    PUSH("Notification"),
    WHATSAPP("WhatsApp"),
    SMS("SMS"),
    EMAIL("E-mail");

    private final String label;

    Channel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
