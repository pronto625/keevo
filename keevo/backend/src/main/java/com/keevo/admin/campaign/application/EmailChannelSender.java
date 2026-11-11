package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Claimed;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Outcome;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Story 21.6 — the email channel: the platform's email providers (19.9), subject = title, text = message. */
@Component
public class EmailChannelSender implements ChannelSender {

    private final EmailSender email;

    public EmailChannelSender(EmailSender email) {
        this.email = email;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public boolean available() {
        return email.isEnabled();
    }

    @Override
    public Result send(PlatformCampaign c, List<Claimed> batch) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Claimed r : batch) {
            DeliveryResult d = email.send(EmailMessage.of(r.address(), c.title(), c.message()));
            outcomes.add(d.delivered() ? new Outcome(Channel.EMAIL, r.userId(), "SENT", null, null)
                    : d.uncertain() ? new Outcome(Channel.EMAIL, r.userId(), "UNCERTAIN", null,
                    "E-mail peut-être reçu (pas de réponse du fournisseur)")
                    : new Outcome(Channel.EMAIL, r.userId(), "FAILED", null, "E-mail non envoyé"));
        }
        return new Result(outcomes, false);
    }
}
