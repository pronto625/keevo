package com.keevo.admin.campaign.adapter.in.scheduling;

import com.keevo.admin.campaign.application.PlatformCampaignDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Story 21.6 — every {@code keevo.campaigns.tick-ms} (2 s), the next batch of the platform campaigns in progress and
 * the scheduled ones that are due. No ShedLock: campaigns and recipients are claimed with SKIP LOCKED and a lease.
 */
@Component
public class PlatformCampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlatformCampaignScheduler.class);

    private final PlatformCampaignDispatcher dispatcher;

    public PlatformCampaignScheduler(PlatformCampaignDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${keevo.campaigns.tick-ms:2000}", initialDelayString = "PT30S")
    public void dispatch() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException e) {
            log.error("[Campaigns] platform campaign dispatch failed ({})", e.getClass().getSimpleName(), e);
        }
    }
}
