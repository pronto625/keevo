package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.model.WhatsAppContent;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Claimed;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Outcome;
import com.keevo.shared.capability.notification.PlatformWhatsApp;
import com.keevo.shared.capability.notification.PlatformWhatsApp.HeaderMedia;
import com.keevo.shared.capability.notification.PlatformWhatsApp.SendResult;
import com.keevo.shared.capability.notification.PlatformWhatsApp.TemplateMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Story 21.6 — the WhatsApp channel: an approved template of the platform number per owner, its variables from the
 * owner's names / company name. Numbers that answered STOP to the platform number are SKIPPED (one query per batch,
 * checked at send time). Meta's throughput refusal leaves the rest of the batch PENDING and pauses the campaign.
 */
@Component
public class WhatsAppChannelSender implements ChannelSender {

    static final String OPTED_OUT = "Désinscrit des messages WhatsApp (STOP)";

    private final PlatformWhatsApp whatsapp;

    public WhatsAppChannelSender(PlatformWhatsApp whatsapp) {
        this.whatsapp = whatsapp;
    }

    @Override
    public Channel channel() {
        return Channel.WHATSAPP;
    }

    @Override
    public boolean available() {
        return whatsapp.isConfigured();
    }

    @Override
    public Result send(PlatformCampaign c, List<Claimed> batch) {
        List<Outcome> outcomes = new ArrayList<>();
        WhatsAppContent w = c.whatsapp();
        Set<String> optedOut = whatsapp.optedOut(batch.stream().map(r -> digits(r.address())).toList());
        boolean rateLimited = false;
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(60); // half the dispatcher's lease
        for (Claimed r : batch) {
            String to = digits(r.address());
            if (rateLimited || java.time.Instant.now().isAfter(deadline)) {
                outcomes.add(new Outcome(Channel.WHATSAPP, r.userId(), "PENDING", null, null));
                continue;
            }
            if (optedOut.contains(to)) {
                outcomes.add(new Outcome(Channel.WHATSAPP, r.userId(), "SKIPPED", null, OPTED_OUT));
                continue;
            }
            List<String> values = w.variables().stream()
                    .map(v -> v.resolve(r.firstName(), r.lastName(), r.companyName())).toList();
            SendResult result = whatsapp.sendTemplate(to, new TemplateMessage(w.templateName(), w.language(),
                    w.bodyText(), values, w.headerFormat(), new HeaderMedia(w.headerMediaId(), w.headerMediaLink())),
                    c.id());
            switch (result.outcome()) {
                case SENT -> outcomes.add(new Outcome(Channel.WHATSAPP, r.userId(), "SENT", result.waMessageId(),
                        null));
                case RATE_LIMITED -> {
                    rateLimited = true;
                    outcomes.add(new Outcome(Channel.WHATSAPP, r.userId(), "PENDING", null, null));
                }
                case UNCERTAIN -> outcomes.add(new Outcome(Channel.WHATSAPP, r.userId(), "UNCERTAIN", null,
                        result.error()));
                default -> outcomes.add(new Outcome(Channel.WHATSAPP, r.userId(), "FAILED", null, result.error()));
            }
        }
        return new Result(outcomes, rateLimited);
    }

    static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }
}
