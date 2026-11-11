package com.keevo.whatsappcloud.campaign.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.6 — the work queue of the client campaigns ({@code public.whatsapp_campaign_jobs}): one row per SCHEDULED or
 * SENDING campaign of any company, so the dispatcher finds the due work in one indexed query, never by visiting the
 * tenant schemas.
 */
public interface CampaignJobRepository {

    /** A claimed job: the campaign, its company and the company's schema. */
    record Job(UUID campaignId, UUID tenantId, String tenantSchema) {
    }

    /** Adds or moves the job of a campaign of the company of {@code tenantSchema}. */
    void schedule(String tenantSchema, UUID campaignId, Instant runAt);

    void remove(UUID campaignId);

    /**
     * Claims up to {@code limit} jobs due at {@code now} (FOR UPDATE SKIP LOCKED) and pushes their next run to
     * {@code leaseUntil}, so another instance or tick never takes them meanwhile; jobs of deleted companies are not
     * returned.
     */
    List<Job> claimDue(Instant now, Instant leaseUntil, int limit);

    void reschedule(UUID campaignId, Instant runAt);
}
