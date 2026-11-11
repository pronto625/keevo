package com.keevo.whatsappcloud.campaign.domain.port.out;

import com.keevo.whatsappcloud.campaign.domain.model.Campaign;
import com.keevo.whatsappcloud.campaign.domain.model.Recipient;
import com.keevo.whatsappcloud.campaign.domain.model.RecipientStatus;
import com.keevo.whatsappcloud.inbox.domain.model.StatusChange;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.6 — the company's campaigns and their recipients (module tables {@code whatsapp_campaigns} /
 * {@code whatsapp_campaign_recipients} of the tenant schema). Every state change is one guarded statement (the
 * expected current status in its {@code WHERE}), so concurrent requests and dispatcher ticks never apply twice.
 */
public interface CampaignRepository {

    /** A campaign with the template it shows: the frozen one once sent, else the chosen template (joined). */
    record CampaignRow(Campaign campaign, String templateName, String templateLanguage, String templateBody,
                       String templateHeader) {
    }

    /** What happened to one claimed recipient. */
    record Outcome(String phone, RecipientStatus status, String waMessageId, String error) {
    }

    void insert(String schema, Campaign campaign);

    /** Saves the editable fields while DRAFT / SCHEDULED; false when the campaign is not editable any more. */
    boolean updateEditable(String schema, Campaign campaign);

    /** Deletes a DRAFT / SCHEDULED / CANCELLED campaign; false otherwise. */
    boolean deleteUnsent(String schema, UUID id);

    Optional<CampaignRow> find(String schema, UUID id);

    /** Newest first; the page after the keyset ({@code beforeCreatedAt}, {@code beforeId}) when given. */
    List<CampaignRow> page(String schema, Instant beforeCreatedAt, UUID beforeId, int limit);

    /** SCHEDULED → DRAFT, date cleared; false otherwise. */
    boolean unschedule(String schema, UUID id, Instant now);

    /** SCHEDULED → CANCELLED; false otherwise. */
    boolean cancel(String schema, UUID id, Instant now);

    /**
     * DRAFT / SCHEDULED → SENDING with the frozen template and the audience size; false when not in those statuses.
     *
     * @throws org.springframework.dao.DuplicateKeyException when another campaign of the company is SENDING
     */
    boolean start(String schema, UUID id, Campaign.FrozenTemplate template, int targeted, Instant now);

    void insertRecipients(String schema, UUID campaignId, List<Recipient> recipients);

    /** Claims up to {@code limit} PENDING recipients (→ SENDING), phone order; skips rows locked elsewhere. */
    List<Recipient> claim(String schema, UUID campaignId, int limit, Instant now);

    /** Recipients left SENDING since before {@code before} (crash) → UNCERTAIN, counted; returns how many. */
    int expireStuck(String schema, UUID campaignId, Instant before, Instant now);

    /** Records the outcomes of a batch (SENDING rows only) and moves the counters, in two statements. */
    void recordOutcomes(String schema, UUID campaignId, List<Outcome> outcomes, Instant now);

    /** Every PENDING recipient → SKIPPED with {@code reason}, counted; returns how many. */
    int skipPending(String schema, UUID campaignId, String reason, Instant now);

    boolean hasOpenRecipients(String schema, UUID campaignId);

    /** SENDING → SENT; false otherwise. */
    boolean complete(String schema, UUID campaignId, Instant now);

    /** Meta's statuses that moved on campaign messages → the counters of their campaigns (one batch). */
    void applyStatusChanges(String schema, List<StatusChange> changes);

    /** Recipients by phone (keyset {@code afterPhone}), with Meta's status of their message. */
    List<Recipient> recipients(String schema, UUID campaignId, String afterPhone, int limit);
}
