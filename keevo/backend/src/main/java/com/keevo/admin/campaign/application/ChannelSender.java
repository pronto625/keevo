package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Claimed;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Outcome;

import java.util.List;

/**
 * Story 21.6 — Strategy: sends one batch of a platform campaign on one channel and says what happened to each
 * recipient (SENT, FAILED, SKIPPED, UNCERTAIN, or PENDING = not tried, retried later). Never throws for a provider
 * failure. {@link #rateLimited} tells the dispatcher to pause the campaign.
 */
public interface ChannelSender {

    Channel channel();

    /** Whether the channel can send at all now (provider configured). */
    boolean available();

    Result send(PlatformCampaign campaign, List<Claimed> batch);

    record Result(List<Outcome> outcomes, boolean rateLimited) {
    }
}
