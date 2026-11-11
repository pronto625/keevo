package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository;
import com.keevo.shared.event.catalog.campaign.PlatformWhatsAppStatusesEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.6 — Meta's statuses on the platform number's campaign messages move the WhatsApp counters of their
 * platform campaigns (Bref {@code CampaignWhatsAppListener}), in the webhook's transaction: deltas aggregated per
 * campaign, one batch of UPDATEs.
 */
@Component
public class PlatformWhatsAppStatusListener {

    private final PlatformCampaignRepository campaigns;

    public PlatformWhatsAppStatusListener(PlatformCampaignRepository campaigns) {
        this.campaigns = campaigns;
    }

    @EventListener
    public void on(PlatformWhatsAppStatusesEvent event) {
        Map<UUID, int[]> deltas = new LinkedHashMap<>();
        for (PlatformWhatsAppStatusesEvent.Change c : event.changes()) {
            if (c.campaignId() == null) {
                continue;
            }
            int[] d = deltas.computeIfAbsent(c.campaignId(), k -> new int[4]);
            d[0] += sent(c.newStatus()) - sent(c.oldStatus());
            d[1] += delivered(c.newStatus()) - delivered(c.oldStatus());
            d[2] += read(c.newStatus()) - read(c.oldStatus());
            d[3] += failed(c.newStatus()) - failed(c.oldStatus());
        }
        campaigns.applyWhatsAppChanges(deltas);
    }

    static int sent(String s) {
        return "SENT".equals(s) || "DELIVERED".equals(s) || "READ".equals(s) ? 1 : 0;
    }

    static int delivered(String s) {
        return "DELIVERED".equals(s) || "READ".equals(s) ? 1 : 0;
    }

    static int read(String s) {
        return "READ".equals(s) ? 1 : 0;
    }

    static int failed(String s) {
        return "FAILED".equals(s) ? 1 : 0;
    }
}
