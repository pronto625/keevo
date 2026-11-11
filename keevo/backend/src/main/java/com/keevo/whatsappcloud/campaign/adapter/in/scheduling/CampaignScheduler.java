package com.keevo.whatsappcloud.campaign.adapter.in.scheduling;

import com.keevo.whatsappcloud.campaign.application.CampaignDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Story 21.6 — every {@code keevo.campaigns.tick-ms} (2 s), sends the next batch of the client campaigns in progress
 * and starts the scheduled ones that are due. No ShedLock: jobs are claimed with SKIP LOCKED and a lease, so several
 * instances share the work without sending a recipient twice.
 */
@Component
public class CampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduler.class);

    private final CampaignDispatcher dispatcher;

    public CampaignScheduler(CampaignDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${keevo.campaigns.tick-ms:2000}", initialDelayString = "PT30S")
    public void dispatch() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException e) {
            log.error("[Campaigns] client campaign dispatch failed ({})", e.getClass().getSimpleName(), e);
        }
    }
}
