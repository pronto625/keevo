package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Claimed;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Outcome;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Story 21.6 — the SMS channel: the platform's SMS providers (19.9, failover order), one message per owner. */
@Component
public class SmsChannelSender implements ChannelSender {

    private final SmsSender sms;

    public SmsChannelSender(SmsSender sms) {
        this.sms = sms;
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public boolean available() {
        return sms.isEnabled();
    }

    @Override
    public Result send(PlatformCampaign c, List<Claimed> batch) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Claimed r : batch) {
            DeliveryResult d = sms.send(SmsMessage.of(r.address(), c.message()));
            outcomes.add(d.delivered() ? new Outcome(Channel.SMS, r.userId(), "SENT", null, null)
                    : d.uncertain() ? new Outcome(Channel.SMS, r.userId(), "UNCERTAIN", null,
                    "SMS peut-être reçu (pas de réponse du fournisseur)")
                    : new Outcome(Channel.SMS, r.userId(), "FAILED", null, "SMS non envoyé"));
        }
        return new Result(outcomes, false);
    }
}
