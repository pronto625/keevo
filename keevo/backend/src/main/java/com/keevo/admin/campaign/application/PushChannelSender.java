package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Claimed;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Outcome;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.6 — the push channel: the owners of a batch grouped by company, one {@link PushNotifier#notifyUsers} per
 * company (the notification is kept in the app's inbox and pushed to the owner's devices of that company; device
 * tokens live in the company's schema). Best effort per company: a failure marks its owners FAILED.
 */
@Component
public class PushChannelSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(PushChannelSender.class);
    public static final String TYPE = "PLATFORM_CAMPAIGN";

    private final PushNotifier push;

    public PushChannelSender(PushNotifier push) {
        this.push = push;
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public boolean available() {
        return true; // the inbox row is always written; delivery to devices depends on the push providers
    }

    @Override
    public Result send(PlatformCampaign c, List<Claimed> batch) {
        Map<String, List<Claimed>> bySchema = new LinkedHashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (Claimed r : batch) {
            if (r.tenantSchema() == null) {
                outcomes.add(new Outcome(Channel.PUSH, r.userId(), "FAILED", null, "Entreprise supprimée"));
            } else {
                bySchema.computeIfAbsent(r.tenantSchema(), k -> new ArrayList<>()).add(r);
            }
        }
        NotificationPayload payload = NotificationPayload.of(TYPE, c.title(), c.message(), null,
                Map.of("title", c.title() == null ? "" : c.title()));
        bySchema.forEach((schema, owners) -> {
            List<UUID> ids = owners.stream().map(Claimed::userId).toList();
            String status = "SENT";
            String error = null;
            try {
                push.notifyUsers(schema, ids, payload);
            } catch (RuntimeException e) {
                log.warn("[Campaigns] platform push failed for tenant {} ({})", schema, e.getClass().getSimpleName());
                status = "FAILED";
                error = "Notification non envoyée";
            }
            for (Claimed r : owners) {
                outcomes.add(new Outcome(Channel.PUSH, r.userId(), status, null, error));
            }
        });
        return new Result(outcomes, false);
    }
}
