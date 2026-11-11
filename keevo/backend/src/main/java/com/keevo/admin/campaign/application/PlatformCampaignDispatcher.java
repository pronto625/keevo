package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.model.PlatformCampaignStatus;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Claimed;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository.Outcome;
import com.keevo.shared.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.6 — sends the platform campaigns batch by batch: each tick claims the due campaigns (SCHEDULED and due, or
 * SENDING; SKIP LOCKED, lease {@link #LEASE}); a due SCHEDULED one is started ({@link PlatformCampaignService#start},
 * retried in {@link #RETRY_LATER} when it cannot), a SENDING one sends its next {@code batch-size} recipients — rows
 * left SENDING by a crash first become UNCERTAIN (never resent) — through the {@link ChannelSender} of each channel
 * (Strategy), records the outcomes in one batch and moves the counters; a channel not configured any more fails its
 * recipients with a French reason; Meta's rate limit pauses the campaign {@link #RATE_LIMIT_PAUSE}; no open recipient
 * left → SENT, audited.
 */
@Service
public class PlatformCampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PlatformCampaignDispatcher.class);

    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration RETRY_LATER = Duration.ofMinutes(5);
    static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);
    static final int CAMPAIGNS_PER_TICK = 5;

    private final PlatformCampaignRepository campaigns;
    private final PlatformCampaignService service;
    private final Map<Channel, ChannelSender> senders = new EnumMap<>(Channel.class);
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Duration tick;

    public PlatformCampaignDispatcher(PlatformCampaignRepository campaigns, PlatformCampaignService service,
                                      List<ChannelSender> senders, PlatformTransactionManager transactionManager,
                                      Clock clock, @Value("${keevo.campaigns.batch-size:40}") int batchSize,
                                      @Value("${keevo.campaigns.tick-ms:2000}") long tickMs) {
        this.campaigns = campaigns;
        this.service = service;
        senders.forEach(s -> this.senders.put(s.channel(), s));
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.tick = Duration.ofMillis(Math.max(200, tickMs));
    }

    public int dispatchDue() {
        Instant now = now();
        List<UUID> due = campaigns.claimDue(now, now.plus(LEASE), CAMPAIGNS_PER_TICK);
        for (UUID id : due) {
            try {
                campaigns.reschedule(id, now().plus(LEASE)); // the lease starts when the campaign is processed
                process(id);
            } catch (RuntimeException e) {
                log.error("[Campaigns] platform campaign {} failed ({}): retried later", id,
                        e.getClass().getSimpleName(), e);
                campaigns.reschedule(id, now().plus(RETRY_LATER));
            }
        }
        return due.size();
    }

    void process(UUID id) {
        PlatformCampaign c = campaigns.find(id).orElse(null);
        if (c == null) {
            return;
        }
        if (c.status() == PlatformCampaignStatus.SCHEDULED) {
            Instant now = now();
            if (c.scheduledAt() != null && c.scheduledAt().isAfter(now)) {
                campaigns.reschedule(id, c.scheduledAt());
                return;
            }
            try {
                service.start(id, null);
            } catch (DomainException e) {
                if (e.getErrorCode() == com.keevo.shared.domain.exception.ErrorCode.CAMPAIGN_NOT_READY
                        || e.getErrorCode() == com.keevo.shared.domain.exception.ErrorCode.CAMPAIGN_NO_RECIPIENT
                        || e.getErrorCode() == com.keevo.shared.domain.exception.ErrorCode.CAMPAIGN_TOO_MANY_RECIPIENTS) {
                    service.backToDraft(c, "Envoi programmé impossible : la campagne repasse en brouillon");
                    return;
                }
                log.warn("[Campaigns] scheduled platform campaign {} not started ({}): retried in 5 min", id,
                        e.getErrorCode());
                campaigns.reschedule(id, now.plus(RETRY_LATER));
            }
            return;
        }
        if (c.status() == PlatformCampaignStatus.SENDING) {
            sendBatch(c);
        }
    }

    void sendBatch(PlatformCampaign c) {
        Instant now = now();
        campaigns.expireStuck(c.id(), now.minus(LEASE), now);
        List<Claimed> batch = campaigns.claimRecipients(c.id(), batchSize, now);
        if (batch.isEmpty()) {
            if (campaigns.hasOpenRecipients(c.id())) {
                campaigns.reschedule(c.id(), now.plus(tick));
                return;
            }
            tx.executeWithoutResult(s -> {
                if (campaigns.complete(c.id(), now)) {
                    service.audit(c, "COMPLETED", null, null, now);
                }
            });
            return;
        }
        Map<Channel, List<Claimed>> byChannel = new EnumMap<>(Channel.class);
        batch.forEach(r -> byChannel.computeIfAbsent(r.channel(), k -> new ArrayList<>()).add(r));
        List<Outcome> outcomes = new ArrayList<>();
        boolean rateLimited = false;
        for (Map.Entry<Channel, List<Claimed>> e : byChannel.entrySet()) {
            ChannelSender sender = senders.get(e.getKey());
            if (sender == null || !sender.available()) {
                e.getValue().forEach(r -> outcomes.add(new Outcome(e.getKey(), r.userId(), "FAILED", null,
                        e.getKey().label() + " non disponible sur la plateforme")));
                continue;
            }
            try {
                ChannelSender.Result result = sender.send(c, e.getValue());
                outcomes.addAll(result.outcomes());
                rateLimited |= result.rateLimited();
            } catch (RuntimeException ex) {
                // part of the batch may have left: at most once — UNCERTAIN, never resent
                log.warn("[Campaigns] platform campaign {} channel {} failed ({})", c.id(), e.getKey(),
                        ex.getClass().getSimpleName());
                e.getValue().forEach(r -> outcomes.add(new Outcome(e.getKey(), r.userId(), "UNCERTAIN", null,
                        "Envoi interrompu : message peut-être reçu")));
            }
        }
        Instant done = now();
        tx.executeWithoutResult(s -> campaigns.recordOutcomes(c.id(), outcomes, done)); // rows + counters together
        campaigns.reschedule(c.id(), done.plus(rateLimited ? RATE_LIMIT_PAUSE : tick));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
