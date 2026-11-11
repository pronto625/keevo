package com.keevo.shared.event.catalog.campaign;

import java.util.List;
import java.util.UUID;

/**
 * Story 21.6 — Meta's delivery statuses that moved on messages of platform campaigns sent from the platform number
 * (one webhook delivery), published by the {@code whatsapp-cloud} module inside the webhook transaction so the
 * platform campaigns ({@code admin}) move their counters (Bref {@code WHATSAPP_CAMPAIGN_MESSAGE_UPDATED_EVENT}). Each
 * change carries the status before and after: counters move by the difference, whatever the order in which Meta's
 * callbacks and the campaign's own bookkeeping arrive. Not tenant-scoped.
 *
 * @param changes one per message whose status moved ({@code SENT} | {@code DELIVERED} | {@code READ} | {@code FAILED})
 */
public record PlatformWhatsAppStatusesEvent(List<Change> changes) {

    public PlatformWhatsAppStatusesEvent {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public record Change(UUID campaignId, String oldStatus, String newStatus) {
    }
}
