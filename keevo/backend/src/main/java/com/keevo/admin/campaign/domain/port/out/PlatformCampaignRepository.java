package com.keevo.admin.campaign.domain.port.out;

import com.keevo.admin.campaign.domain.model.AudienceMember;
import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.ChannelStats;
import com.keevo.admin.campaign.domain.model.HistoryEntry;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.6 — the platform campaigns ({@code public.platform_campaigns}), their frozen audience
 * ({@code platform_campaign_recipients}), counters per channel ({@code platform_campaign_stats}) and audit
 * ({@code platform_campaign_events}). Every state change is one guarded statement.
 */
public interface PlatformCampaignRepository {

    /** One recipient claimed for sending: channel, owner, company schema (push), address, names. */
    record Claimed(Channel channel, UUID userId, UUID tenantId, String tenantSchema, String address, String firstName,
                   String lastName, String companyName) {
    }

    /** What happened to one claimed recipient ({@code status} PENDING = released, not sent). */
    record Outcome(Channel channel, UUID userId, String status, String waMessageId, String error) {
    }

    void insert(PlatformCampaign campaign);

    boolean updateEditable(PlatformCampaign campaign);

    boolean deleteUnsent(UUID id);

    Optional<PlatformCampaign> find(UUID id);

    List<PlatformCampaign> page(Instant beforeCreatedAt, UUID beforeId, int limit);

    /** SCHEDULED → DRAFT, date cleared. */
    boolean unschedule(UUID id, Instant now);

    /** SCHEDULED → CANCELLED. */
    boolean cancel(UUID id, Instant now);

    /** DRAFT / SCHEDULED → SENDING with the audience size, due now; false otherwise. */
    boolean start(UUID id, int targeted, Instant now);

    /** The recipients of every channel (batched insert) and the per-channel targeted counters. */
    void insertAudience(UUID id, Map<Channel, List<AudienceMember>> byChannel);

    /** Claims due campaigns (SCHEDULED due or SENDING), lease until {@code leaseUntil}, SKIP LOCKED. */
    List<UUID> claimDue(Instant now, Instant leaseUntil, int limit);

    void reschedule(UUID id, Instant at);

    List<Claimed> claimRecipients(UUID id, int limit, Instant now);

    /** SENDING since before {@code before} → UNCERTAIN, counted per channel. */
    int expireStuck(UUID id, Instant before, Instant now);

    /** The outcomes of a batch (one JDBC batch) and the per-channel counters (one batch). */
    void recordOutcomes(UUID id, List<Outcome> outcomes, Instant now);

    boolean hasOpenRecipients(UUID id);

    /** SENDING → SENT (no next run). */
    boolean complete(UUID id, Instant now);

    Map<UUID, List<ChannelStats>> stats(Collection<UUID> ids);

    /** Meta's statuses that moved on WhatsApp messages of platform campaigns → their counters. */
    void applyWhatsAppChanges(Map<UUID, int[]> deltas);

    void addEvent(UUID campaignId, String campaignName, String action, UUID actorId, String detail, Instant at);

    List<HistoryEntry> history(UUID campaignId);
}
