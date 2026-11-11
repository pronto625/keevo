package com.keevo.admin.campaign.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.6 — a campaign of the super-admin to the owners of a segment of companies (Bref {@code Campaign}), over one
 * or more channels: push (with the app's notification inbox), WhatsApp (platform number, approved template), SMS and
 * email ({@code title} = notification title / email subject, {@code message} = their text).
 */
public record PlatformCampaign(UUID id, String name, String description, PlatformCampaignStatus status,
                               List<Channel> channels, String title, String message, Segment segment,
                               WhatsAppContent whatsapp, Instant scheduledAt, Instant startedAt, Instant sentAt,
                               int targetedCount, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public PlatformCampaign {
        channels = channels == null ? List.of() : List.copyOf(channels);
        segment = segment == null ? Segment.ALL : segment;
    }
}
