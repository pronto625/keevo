package com.keevo.whatsappcloud.campaign.application;

import com.keevo.crm.api.ClientQueryPort;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.capability.notification.PlatformWhatsApp.HeaderMedia;
import com.keevo.shared.capability.notification.PlatformWhatsApp.SendResult;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.campaign.WhatsAppCampaignChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.EntitlementService;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService.Credentials;
import com.keevo.whatsappcloud.campaign.domain.model.Campaign;
import com.keevo.whatsappcloud.campaign.domain.model.Recipient;
import com.keevo.whatsappcloud.campaign.domain.model.RecipientStatus;
import com.keevo.whatsappcloud.campaign.domain.model.VariableMapping;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignJobRepository;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignJobRepository.Job;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignRepository;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignRepository.Outcome;
import com.keevo.whatsappcloud.config.MetaSends;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.inbox.domain.model.ChatMessage;
import com.keevo.whatsappcloud.inbox.domain.port.out.WhatsAppInboxRepository;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
import com.keevo.whatsappcloud.template.domain.model.TemplateComponents;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.6 — sends the client campaigns, asynchronously, batch by batch (port of Bref's background loop, made
 * cluster-safe and resumable). Each tick claims the due jobs of every company from the public queue (one indexed
 * statement, lease of {@link #LEASE}); per job:
 * <ul>
 *   <li>SCHEDULED and due → {@link CampaignService#start} (audience frozen); not possible now (number disconnected,
 *       template not approved, another campaign sending, quota…) → retried in {@link #RETRY_LATER};</li>
 *   <li>SENDING → recipients left SENDING by a crash become UNCERTAIN (never resent); then at most {@code batch-size}
 *       PENDING recipients are claimed, their consent re-checked in one query (withdrawn / STOP → SKIPPED), the
 *       WhatsApp-messages quota probed for the batch (refused → the batch and every pending recipient SKIPPED, the
 *       campaign ends), each message sent from the company number (the sent message stored at once with its
 *       campaign, so Meta's statuses always find it); Meta's throughput refusal puts the rest of the batch back to
 *       PENDING and pauses the campaign {@link #RATE_LIMIT_PAUSE}; the outcomes recorded in one batch, the counters
 *       moved, the messages counted on the quota (own transaction: a concurrent overrun is logged, bounded);</li>
 *   <li>no open recipient left → SENT, job removed, audited.</li>
 * </ul>
 * Throughput: ≤ batch-size messages per campaign per tick ({@code keevo.campaigns.*}); one SENDING campaign per
 * company. Queries per batch are constant (claim, eligibility, quota probe, outcomes, counters, conversations, quota);
 * one insert per message sent (the message must exist before Meta's status callback can arrive).
 */
@Service
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);

    static final Duration LEASE = Duration.ofMinutes(2);
    /** A batch stops sending after half the lease: the rest goes back to PENDING (never claimed twice). */
    static final Duration SEND_BUDGET = LEASE.dividedBy(2);
    static final Duration RETRY_LATER = Duration.ofMinutes(5);
    static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);
    static final int JOBS_PER_TICK = 20;
    /** Start refusals that waiting will not fix: the scheduled campaign goes back to DRAFT (job removed). */
    static final java.util.Set<ErrorCode> PERMANENT = java.util.EnumSet.of(ErrorCode.CAMPAIGN_NOT_READY,
            ErrorCode.CAMPAIGN_NO_RECIPIENT, ErrorCode.CAMPAIGN_TOO_MANY_RECIPIENTS,
            ErrorCode.WHATSAPP_TEMPLATE_NOT_FOUND, ErrorCode.WHATSAPP_TEMPLATE_NOT_APPROVED);
    static final String NO_CONSENT = "Le client ne souhaite plus recevoir d'offres";
    static final String QUOTA_REACHED = "Quota de messages WhatsApp atteint";

    private final CampaignRepository campaigns;
    private final CampaignJobRepository jobs;
    private final CampaignService service;
    private final WhatsAppInboxService inbox;
    private final WhatsAppInboxRepository inboxRepository;
    private final MetaGraphClient graph;
    private final ClientQueryPort clients;
    private final UsageQuotaPort quota;
    private final EntitlementService entitlements;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Duration tick;

    public CampaignDispatcher(CampaignRepository campaigns, CampaignJobRepository jobs, CampaignService service,
                              WhatsAppInboxService inbox, WhatsAppInboxRepository inboxRepository,
                              MetaGraphClient graph, ClientQueryPort clients, UsageQuotaPort quota,
                              EntitlementService entitlements, ApplicationEventPublisher events,
                              PlatformTransactionManager transactionManager, Clock clock,
                              @Value("${keevo.campaigns.batch-size:40}") int batchSize,
                              @Value("${keevo.campaigns.tick-ms:2000}") long tickMs) {
        this.campaigns = campaigns;
        this.jobs = jobs;
        this.service = service;
        this.inbox = inbox;
        this.inboxRepository = inboxRepository;
        this.graph = graph;
        this.clients = clients;
        this.quota = quota;
        this.entitlements = entitlements;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.tick = Duration.ofMillis(Math.max(200, tickMs));
    }

    /** One tick: the due jobs of every company; returns how many were processed. */
    public int dispatchDue() {
        Instant now = now();
        List<Job> due = jobs.claimDue(now, now.plus(LEASE), JOBS_PER_TICK);
        for (Job job : due) {
            String previous = TenantContext.getCurrentTenant();
            TenantContext.setCurrentTenant(job.tenantSchema());
            try {
                // the lease starts when the job is really processed (a slow job before it never eats it)
                jobs.reschedule(job.campaignId(), now().plus(LEASE));
                process(job);
            } catch (RuntimeException e) {
                log.error("[Campaigns] campaign job of tenant {} failed ({}): retried later", job.tenantSchema(),
                        e.getClass().getSimpleName(), e);
                jobs.reschedule(job.campaignId(), now().plus(RETRY_LATER));
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setCurrentTenant(previous);
                }
            }
        }
        return due.size();
    }

    void process(Job job) {
        String schema = job.tenantSchema();
        var row = campaigns.find(schema, job.campaignId());
        if (row.isEmpty()) {
            jobs.remove(job.campaignId());
            return;
        }
        Campaign c = row.get().campaign();
        switch (c.status()) {
            case SCHEDULED -> startDue(schema, c);
            case SENDING -> sendBatch(schema, c);
            default -> jobs.remove(c.id());
        }
    }

    private void startDue(String schema, Campaign c) {
        Instant now = now();
        if (c.scheduledAt() == null) {
            jobs.remove(c.id());
            return;
        }
        if (c.scheduledAt().isAfter(now)) {
            jobs.reschedule(c.id(), c.scheduledAt());
            return;
        }
        try {
            service.start(schema, c.id(), null);
        } catch (DomainException e) {
            if (PERMANENT.contains(e.getErrorCode())) {
                // cannot leave as it is (template gone / not approved, nobody, too many): back to draft, owner fixes it
                log.warn("[Campaigns] scheduled campaign of tenant {} back to draft ({})", schema, e.getErrorCode());
                service.backToDraft(schema, c);
                return;
            }
            log.warn("[Campaigns] scheduled campaign of tenant {} not started yet ({}): retried in 5 min", schema,
                    e.getErrorCode());
            jobs.reschedule(c.id(), now.plus(RETRY_LATER));
        }
    }

    void sendBatch(String schema, Campaign c) {
        Instant now = now();
        campaigns.expireStuck(schema, c.id(), now.minus(LEASE), now);
        Credentials creds;
        try {
            if (!entitlements.isEnabled(schema, WhatsAppCloudModule.ID)) {
                throw new DomainException(ErrorCode.MODULE_NOT_ENTITLED);
            }
            creds = inbox.credentials(schema);
        } catch (DomainException e) {
            log.warn("[Campaigns] campaign of tenant {} paused ({}): retried in 5 min", schema, e.getErrorCode());
            jobs.reschedule(c.id(), now.plus(RETRY_LATER));
            return;
        }
        List<Recipient> batch = campaigns.claim(schema, c.id(), batchSize, now);
        if (batch.isEmpty()) {
            finishOrWait(schema, c, now);
            return;
        }
        Set<UUID> eligible;
        try {
            eligible = clients.marketingEligible(schema, batch.stream().map(Recipient::clientId).toList());
        } catch (RuntimeException e) {
            release(schema, c, batch, now);
            throw e;
        }
        List<Recipient> toSend = batch.stream().filter(r -> eligible.contains(r.clientId())).toList();
        List<Outcome> outcomes = new ArrayList<>();
        batch.stream().filter(r -> !eligible.contains(r.clientId()))
                .forEach(r -> outcomes.add(new Outcome(r.phone(), RecipientStatus.SKIPPED, null, NO_CONSENT)));
        try {
            service.probeQuota(schema, toSend.size());
        } catch (DomainException e) {
            if (e.getErrorCode() != ErrorCode.QUOTA_EXCEEDED) {
                release(schema, c, batch, now);
                throw e;
            }
            toSend.forEach(r -> outcomes.add(new Outcome(r.phone(), RecipientStatus.SKIPPED, null, QUOTA_REACHED)));
            tx.executeWithoutResult(s -> {
                campaigns.recordOutcomes(schema, c.id(), outcomes, now);
                campaigns.skipPending(schema, c.id(), QUOTA_REACHED, now);
            });
            finishOrWait(schema, c, now);
            return;
        }
        Campaign.FrozenTemplate t = c.frozen();
        HeaderMedia header = new HeaderMedia(c.headerMediaId(), c.headerMediaLink());
        List<ChatMessage> sent = new ArrayList<>();
        boolean rateLimited = false;
        Instant deadline = now.plus(SEND_BUDGET);
        for (Recipient r : toSend) {
            if (rateLimited || now().isAfter(deadline)) { // Meta's limit, or the batch outlasting half the lease
                outcomes.add(new Outcome(r.phone(), RecipientStatus.PENDING, null, null));
                continue;
            }
            List<String> values = values(c.variables(), r);
            SendResult result;
            try {
                result = MetaSends.send(() -> graph.sendTemplate(creds.phoneNumberId(), creds.token(),
                        r.phone(), t.name(), t.language(), TemplateComponents.build(t.bodyText(), values,
                                t.headerFormat(), header)));
            } catch (RuntimeException e) { // never lose what this batch already sent: maybe sent, never resent
                log.warn("[Campaigns] send failed unexpectedly for tenant {} ({})", schema, e.getClass().getSimpleName());
                result = SendResult.of(SendResult.Outcome.UNCERTAIN, "Envoi interrompu : message peut-être reçu");
            }
            switch (result.outcome()) {
                case SENT -> {
                    outcomes.add(new Outcome(r.phone(), RecipientStatus.SENT, result.waMessageId(), null));
                    sent.add(store(schema, c, r, TemplateText.render(t.bodyText(), values), result.waMessageId()));
                }
                case RATE_LIMITED -> {
                    rateLimited = true;
                    outcomes.add(new Outcome(r.phone(), RecipientStatus.PENDING, null, null));
                }
                case UNCERTAIN -> outcomes.add(new Outcome(r.phone(), RecipientStatus.UNCERTAIN, null,
                        result.error()));
                default -> outcomes.add(new Outcome(r.phone(), RecipientStatus.FAILED, null, result.error()));
            }
        }
        Instant done = now();
        tx.executeWithoutResult(s -> {
            campaigns.recordOutcomes(schema, c.id(), outcomes, done);
            upsertConversations(schema, sent);
        });
        count(schema, sent.size());
        if (rateLimited) {
            log.warn("[Campaigns] Meta's rate limit reached for tenant {}: campaign paused {} s", schema,
                    RATE_LIMIT_PAUSE.toSeconds());
            jobs.reschedule(c.id(), done.plus(RATE_LIMIT_PAUSE));
        } else {
            jobs.reschedule(c.id(), done.plus(tick));
        }
    }

    /** Nothing was sent: the claimed recipients go back to PENDING (never left SENDING to become UNCERTAIN). */
    private void release(String schema, Campaign c, List<Recipient> batch, Instant now) {
        campaigns.recordOutcomes(schema, c.id(), batch.stream()
                .map(r -> new Outcome(r.phone(), RecipientStatus.PENDING, null, null)).toList(), now);
    }

    private void finishOrWait(String schema, Campaign c, Instant now) {
        if (campaigns.hasOpenRecipients(schema, c.id())) {
            jobs.reschedule(c.id(), now.plus(tick)); // rows claimed elsewhere, or SENDING not expired yet
            return;
        }
        tx.executeWithoutResult(s -> {
            if (campaigns.complete(schema, c.id(), now)) {
                jobs.remove(c.id());
                events.publishEvent(new WhatsAppCampaignChangedEvent(null, schema, c.id(), c.name(),
                        WhatsAppCampaignChangedEvent.Change.COMPLETED, null, now));
            }
        });
    }

    /** The message stored at once (its status callback may come before the end of the batch). */
    private ChatMessage store(String schema, Campaign c, Recipient r, String body, String waId) {
        String stored = body == null ? null : body.length() > 8192 ? body.substring(0, 8192) : body;
        ChatMessage m = new ChatMessage(UUID.randomUUID(), r.phone(), ChatMessage.Direction.OUT, "template", stored,
                waId, "SENT", null, c.createdBy(), now(), c.id());
        try {
            inboxRepository.insertAll(schema, List.of(m));
        } catch (RuntimeException e) {
            log.warn("[Campaigns] sent message not stored for tenant {} ({})", schema, e.getClass().getSimpleName());
        }
        return m;
    }

    private void upsertConversations(String schema, List<ChatMessage> sent) {
        if (sent.isEmpty()) {
            return;
        }
        var deltas = new java.util.LinkedHashMap<String, com.keevo.whatsappcloud.inbox.domain.model.ConversationDelta>();
        for (ChatMessage m : sent) {
            deltas.put(m.contactPhone(), new com.keevo.whatsappcloud.inbox.domain.model.ConversationDelta(
                    m.contactPhone(), null, WhatsAppInboxService.preview(m.body()), m.type(),
                    ChatMessage.Direction.OUT, m.createdAt(), 0, null));
        }
        inboxRepository.upsertConversations(schema, new ArrayList<>(deltas.values()));
    }

    /** Counts the sent messages on the company's quota (own transaction; a concurrent overrun is absorbed). */
    private void count(String schema, int n) {
        if (n <= 0) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> quota.assertAndIncrementForSchema(schema, UsageMetric.WHATSAPP_MESSAGES, n));
        } catch (DomainException e) {
            log.warn("[Campaigns] {} WhatsApp message(s) already sent over the quota of tenant {} (concurrent use)",
                    n, schema);
        }
    }

    static List<String> values(List<VariableMapping> variables, Recipient r) {
        return variables.stream().map(v -> v.resolve(r.clientName(), r.storeName())).toList();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
