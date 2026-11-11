package com.keevo.whatsappcloud.campaign.application;

import com.keevo.crm.api.ClientQueryPort;
import com.keevo.crm.api.MarketingRecipient;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.capability.notification.PlatformWhatsApp.HeaderMedia;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.campaign.WhatsAppCampaignChangedEvent;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import com.keevo.whatsappcloud.campaign.domain.model.Campaign;
import com.keevo.whatsappcloud.campaign.domain.model.CampaignStatus;
import com.keevo.whatsappcloud.campaign.domain.model.Recipient;
import com.keevo.whatsappcloud.campaign.domain.model.VariableMapping;
import com.keevo.whatsappcloud.campaign.domain.model.VariableSource;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignJobRepository;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignRepository;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignRepository.CampaignRow;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.inbox.domain.model.ContactPhone;
import com.keevo.whatsappcloud.template.application.WhatsAppTemplateService;
import com.keevo.whatsappcloud.template.domain.model.MessageTemplate;
import com.keevo.whatsappcloud.template.domain.model.TemplateComponents;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.6 — the company's WhatsApp campaigns to its consenting clients (port of Bref
 * {@code CustomerCampaignService}): owner CRUD while DRAFT / SCHEDULED, audience preview, send (now, or at
 * {@code scheduledAt} through the work queue), cancel a scheduled one, recipients follow-up.
 *
 * <p>Starting a send ({@link #start}) is the one moment the audience is computed and frozen: the template must be
 * approved (its name, language, body and header frozen too), the number connected, the audience of eligible clients
 * (crm: consent given, no STOP, not archived; one per phone) non-empty and ≤ {@value #MAX_RECIPIENTS}, and the
 * WhatsApp-messages quota able to take it (probe, nothing counted). Then one transaction moves the campaign DRAFT /
 * SCHEDULED → SENDING (guarded; one SENDING per company by a unique index), inserts the recipients in batches and
 * queues the job; the messages leave asynchronously ({@link CampaignDispatcher}).
 *
 * <p>Costs: preview / start = 1 audience query (partial index, LIMIT 5 001) + 1 store-names query; list = 1 keyset
 * query; start = + 1 guarded UPDATE + batched recipient insert + 1 job upsert.
 */
@Service
public class CampaignService {

    public static final int MAX_RECIPIENTS = 5_000;
    public static final int PAGE = 50;
    public static final int RECIPIENTS_PAGE = 100;
    static final int MAX_NAME = 100;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_STORES = 100;
    static final int MAX_VARIABLES = 20;
    static final Duration MAX_SCHEDULE = Duration.ofDays(365);
    /** Meta keeps an uploaded media 30 days: a scheduled campaign using one must leave before (margin of a day). */
    static final Duration MEDIA_LIFETIME = Duration.ofDays(29);

    /** What the owner edits (create, or the fields present in a PATCH). */
    public record Draft(String name, String description, List<UUID> storeIds, UUID templateId,
                        List<VariableInput> variables, String headerMediaId, String headerMediaLink,
                        Instant scheduledAt) {
    }

    /** One variable as the app sends it: source {@code static | clientName | storeName} (Bref spellings too). */
    public record VariableInput(String source, String value) {
    }

    /** Which fields a PATCH carries (absent = unchanged; {@code scheduledAt} present and null = not scheduled). */
    public record Presence(boolean name, boolean description, boolean storeIds, boolean template, boolean variables,
                           boolean headerMedia, boolean scheduledAt) {
        public static final Presence ALL = new Presence(true, true, true, true, true, true, true);
    }

    /** The audience preview: eligible clients, one per phone; {@code overLimit} above {@value #MAX_RECIPIENTS}. */
    public record Preview(int count, boolean overLimit) {
    }

    private final CampaignRepository campaigns;
    private final CampaignJobRepository jobs;
    private final WhatsAppTemplateService templates;
    private final WhatsAppInboxService inbox;
    private final ClientQueryPort clients;
    private final StoreQueryPort stores;
    private final UsageQuotaPort quota;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CampaignService(CampaignRepository campaigns, CampaignJobRepository jobs,
                           WhatsAppTemplateService templates, WhatsAppInboxService inbox, ClientQueryPort clients,
                           StoreQueryPort stores, UsageQuotaPort quota, ApplicationEventPublisher events,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.campaigns = campaigns;
        this.jobs = jobs;
        this.templates = templates;
        this.inbox = inbox;
        this.clients = clients;
        this.stores = stores;
        this.quota = quota;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ── reads ─────────────────────────────────────────────────────────────────────────────────────────────────────

    public List<CampaignRow> list(String schema, Instant beforeCreatedAt, UUID beforeId) {
        return campaigns.page(schema, beforeCreatedAt, beforeCreatedAt == null ? null : beforeId, PAGE);
    }

    public CampaignRow get(String schema, UUID id) {
        return campaigns.find(schema, id).orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND));
    }

    public List<Recipient> recipients(String schema, UUID id, String afterPhone) {
        get(schema, id);
        String after = afterPhone == null || afterPhone.isBlank() ? null : ContactPhone.require(afterPhone);
        return campaigns.recipients(schema, id, after, RECIPIENTS_PAGE);
    }

    /** The owner's approved templates a campaign can use (Bref {@code customer-campaigns/templates}). */
    public List<MessageTemplate> usableTemplates(String schema) {
        return templates.list(schema).stream().filter(t -> MessageTemplate.APPROVED.equals(t.status())).toList();
    }

    /** Store names keyed by id (the stores a campaign targets, for display). One query. */
    public Map<UUID, String> storeNames(String schema, java.util.Collection<UUID> ids) {
        return ids.isEmpty() ? Map.of() : stores.namesByIds(schema, ids);
    }

    public Preview preview(String schema, List<UUID> storeIds) {
        List<UUID> ids = checkStores(schema, storeIds);
        int n = clients.countMarketingAudience(schema, ids, MAX_RECIPIENTS + 1);
        return new Preview(Math.min(n, MAX_RECIPIENTS), n > MAX_RECIPIENTS);
    }

    // ── writes ────────────────────────────────────────────────────────────────────────────────────────────────────

    public CampaignRow create(String schema, UUID actorId, Draft d) {
        Instant now = now();
        Campaign c = validated(schema, Presence.ALL, new Campaign(UUID.randomUUID(), d.name(), d.description(), CampaignStatus.DRAFT,
                d.storeIds(), d.templateId(), variables(d.variables()), d.headerMediaId(), d.headerMediaLink(),
                d.scheduledAt(), null, null, null, null, actorId, now, now), now);
        tx.executeWithoutResult(s -> {
            campaigns.insert(schema, c);
            if (c.status() == CampaignStatus.SCHEDULED) {
                jobs.schedule(schema, c.id(), c.scheduledAt());
            }
            publish(actorId, schema, c, WhatsAppCampaignChangedEvent.Change.CREATED, null);
        });
        return get(schema, c.id());
    }

    public CampaignRow update(String schema, UUID actorId, UUID id, Draft d, Presence p) {
        Campaign current = get(schema, id).campaign();
        if (!current.status().editable()) {
            throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
        }
        Instant now = now();
        Campaign merged = validated(schema, p, new Campaign(id, p.name() ? d.name() : current.name(),
                p.description() ? d.description() : current.description(), current.status(),
                p.storeIds() ? d.storeIds() : current.storeIds(), p.template() ? d.templateId() : current.templateId(),
                p.variables() ? variables(d.variables()) : current.variables(),
                p.headerMedia() ? d.headerMediaId() : current.headerMediaId(),
                p.headerMedia() ? d.headerMediaLink() : current.headerMediaLink(),
                p.scheduledAt() ? d.scheduledAt() : current.scheduledAt(), null, null, null, null,
                current.createdBy(), current.createdAt(), now), now);
        tx.executeWithoutResult(s -> {
            if (!campaigns.updateEditable(schema, merged)) {
                throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
            }
            if (merged.status() == CampaignStatus.SCHEDULED) {
                jobs.schedule(schema, id, merged.scheduledAt());
            } else {
                jobs.remove(id);
            }
            publish(actorId, schema, merged, WhatsAppCampaignChangedEvent.Change.UPDATED, null);
        });
        return get(schema, id);
    }

    public void delete(String schema, UUID actorId, UUID id) {
        Campaign c = get(schema, id).campaign();
        tx.executeWithoutResult(s -> {
            if (!campaigns.deleteUnsent(schema, id)) {
                throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
            }
            jobs.remove(id);
            publish(actorId, schema, c, WhatsAppCampaignChangedEvent.Change.DELETED, null);
        });
    }

    public CampaignRow cancel(String schema, UUID actorId, UUID id) {
        Campaign c = get(schema, id).campaign();
        tx.executeWithoutResult(s -> {
            if (!campaigns.cancel(schema, id, now())) {
                throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
            }
            jobs.remove(id);
            publish(actorId, schema, c, WhatsAppCampaignChangedEvent.Change.CANCELLED, null);
        });
        return get(schema, id);
    }

    /**
     * A due scheduled campaign that cannot leave as it is goes back to DRAFT (date cleared, job removed, audited
     * « modifiée » by the system): the owner sees it and fixes it, instead of a retry every 5 minutes.
     */
    public void backToDraft(String schema, Campaign c) {
        tx.executeWithoutResult(s -> {
            if (campaigns.unschedule(schema, c.id(), now())) {
                jobs.remove(c.id());
                publish(null, schema, c, WhatsAppCampaignChangedEvent.Change.UPDATED,
                        "Envoi programmé impossible : la campagne repasse en brouillon");
            }
        });
    }

    /** POST /send — starts the send now (also a SCHEDULED campaign, ahead of its date). */
    public CampaignRow send(String schema, UUID actorId, UUID id) {
        start(schema, id, actorId);
        return get(schema, id);
    }

    /**
     * Freezes the audience and the template and moves the campaign to SENDING (see the class comment). Also called
     * by the dispatcher for a SCHEDULED campaign that is due ({@code actorId} {@code null}).
     *
     * @throws DomainException {@code CAMPAIGN_NOT_FOUND} 404, {@code CAMPAIGN_ALREADY_SENT} 409,
     *                         {@code CAMPAIGN_SENDING_IN_PROGRESS} 409, {@code CAMPAIGN_NOT_READY} 422,
     *                         {@code WHATSAPP_NOT_CONNECTED} 409, {@code WHATSAPP_TEMPLATE_NOT_APPROVED} 409,
     *                         {@code CAMPAIGN_NO_RECIPIENT} 422, {@code CAMPAIGN_TOO_MANY_RECIPIENTS} 422,
     *                         {@code QUOTA_EXCEEDED} 403
     */
    public void start(String schema, UUID id, UUID actorId) {
        Campaign c = get(schema, id).campaign();
        if (!c.status().editable()) {
            throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
        }
        if (c.templateId() == null) {
            throw new DomainException(ErrorCode.CAMPAIGN_NOT_READY);
        }
        MessageTemplate t = templates.approved(schema, c.templateId());
        String problem = readiness(t, c);
        if (problem != null) {
            throw DomainException.userFacing(ErrorCode.CAMPAIGN_NOT_READY, problem);
        }
        inbox.credentials(schema); // WHATSAPP_NOT_CONNECTED before anything else
        List<MarketingRecipient> audience = clients.marketingAudience(schema, c.storeIds(), MAX_RECIPIENTS + 1);
        if (audience.isEmpty()) {
            throw new DomainException(ErrorCode.CAMPAIGN_NO_RECIPIENT);
        }
        if (audience.size() > MAX_RECIPIENTS) {
            throw DomainException.userFacing(ErrorCode.CAMPAIGN_TOO_MANY_RECIPIENTS,
                    "Une campagne est limitée à 5 000 destinataires : choisissez moins de boutiques");
        }
        probeQuota(schema, audience.size());
        Set<UUID> storeIds = new HashSet<>();
        audience.forEach(r -> {
            if (r.storeId() != null) {
                storeIds.add(r.storeId());
            }
        });
        Map<UUID, String> names = storeIds.isEmpty() ? Map.of() : stores.namesByIds(schema, storeIds);
        String fallbackStore = defaultStoreName(schema, names);
        // one recipient per Meta number (two E.164 spellings of one number collapse): the frozen count is exact
        Map<String, Recipient> byPhone = new java.util.LinkedHashMap<>();
        for (MarketingRecipient r : audience) {
            String phone = ContactPhone.digitsOrNull(r.phoneE164());
            if (phone != null) {
                byPhone.putIfAbsent(phone, Recipient.pending(phone, r.clientId(), bounded(r.name()),
                        bounded(r.storeId() == null ? fallbackStore : names.getOrDefault(r.storeId(), fallbackStore))));
            }
        }
        if (byPhone.isEmpty()) {
            throw new DomainException(ErrorCode.CAMPAIGN_NO_RECIPIENT);
        }
        List<Recipient> recipients = new ArrayList<>(byPhone.values());
        Instant now = now();
        Campaign.FrozenTemplate frozen = new Campaign.FrozenTemplate(t.name(), t.language(), t.bodyText(),
                t.headerFormat());
        try {
            tx.executeWithoutResult(s -> {
                if (!campaigns.start(schema, id, frozen, recipients.size(), now)) {
                    throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
                }
                campaigns.insertRecipients(schema, id, recipients);
                jobs.schedule(schema, id, now);
                publish(actorId, schema, c, WhatsAppCampaignChangedEvent.Change.SEND_STARTED,
                        recipients.size() + (recipients.size() > 1 ? " destinataires" : " destinataire"));
            });
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("uq_whatsapp_campaigns_sending")) {
                throw new DomainException(ErrorCode.CAMPAIGN_SENDING_IN_PROGRESS);
            }
            throw e;
        }
    }

    /** The French reason a campaign cannot leave with this template, or {@code null}. */
    static String readiness(MessageTemplate t, Campaign c) {
        int expected = TemplateText.variables(t.bodyText()).size();
        if (c.variables().size() != expected) {
            return "Ce modèle attend " + expected + (expected > 1 ? " variables" : " variable")
                    + " : complétez la campagne";
        }
        return TemplateComponents.headerProblem(t.headerFormat(),
                new HeaderMedia(c.headerMediaId(), c.headerMediaLink()));
    }

    /** Probes the WhatsApp-messages quota for {@code n} messages (nothing counted). */
    void probeQuota(String schema, int n) {
        if (n <= 0) {
            return;
        }
        tx.executeWithoutResult(s -> {
            quota.assertAndIncrementForSchema(schema, UsageMetric.WHATSAPP_MESSAGES, n);
            s.setRollbackOnly();
        });
    }

    // ── validation ────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code p}: the fields changed now — stores and date are re-checked only when given (a deactivated store or a
     *  passed date of the saved campaign never blocks renaming it). */
    private Campaign validated(String schema, Presence p, Campaign c, Instant now) {
        String name = c.name() == null ? "" : c.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new InvalidRequestParameterException("name", "length",
                    "Le nom de la campagne est obligatoire (100 caractères au plus)");
        }
        String description = c.description() == null || c.description().isBlank() ? null : c.description().strip();
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new InvalidRequestParameterException("description", "length",
                    "La description ne doit pas dépasser 500 caractères");
        }
        List<UUID> storeIds = p.storeIds() ? checkStores(schema, c.storeIds()) : c.storeIds();
        if (c.variables().size() > MAX_VARIABLES) {
            throw new InvalidRequestParameterException("variables", "size", "20 variables au plus");
        }
        String mediaId = blankToNull(c.headerMediaId());
        String mediaLink = blankToNull(c.headerMediaLink());
        if (c.templateId() != null) {
            MessageTemplate t = templates.get(schema, c.templateId());
            int expected = TemplateText.variables(t.bodyText()).size();
            if (c.variables().size() != expected) {
                throw new InvalidRequestParameterException("variables", "count", "Ce modèle attend " + expected
                        + (expected > 1 ? " variables" : " variable"));
            }
            if (mediaId != null || mediaLink != null) {
                String problem = TemplateComponents.headerProblem(t.headerFormat(), new HeaderMedia(mediaId, mediaLink));
                if (problem != null) {
                    throw new InvalidRequestParameterException("headerMedia", "media", problem);
                }
            }
        } else if (mediaId != null || mediaLink != null) {
            throw new InvalidRequestParameterException("headerMedia", "media", "Choisissez d'abord un modèle");
        }
        Instant scheduledAt = c.scheduledAt() == null ? null : c.scheduledAt().truncatedTo(ChronoUnit.MICROS);
        if (p.scheduledAt() && scheduledAt != null
                && (!scheduledAt.isAfter(now) || scheduledAt.isAfter(now.plus(MAX_SCHEDULE)))) {
            throw new InvalidRequestParameterException("scheduledAt", "range",
                    "La date d'envoi doit être dans le futur (un an au plus)");
        }
        if (mediaId != null && scheduledAt != null && scheduledAt.isAfter(now.plus(MEDIA_LIFETIME))) {
            throw new InvalidRequestParameterException("headerMediaId", "lifetime",
                    "Un fichier joint expire après 30 jours : programmez l'envoi plus tôt ou indiquez un lien https");
        }
        int targeted = clients.countMarketingAudience(schema, storeIds, MAX_RECIPIENTS + 1);
        return new Campaign(c.id(), name, description, scheduledAt == null ? CampaignStatus.DRAFT
                : CampaignStatus.SCHEDULED, storeIds, c.templateId(), c.variables(), mediaId, mediaLink, scheduledAt,
                null, null, new Campaign.Counters(Math.min(targeted, MAX_RECIPIENTS), 0, 0, 0, 0, 0, 0), null,
                c.createdBy(), c.createdAt(), c.updatedAt());
    }

    private List<UUID> checkStores(String schema, List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> distinct = new LinkedHashSet<>(storeIds);
        distinct.remove(null);
        if (distinct.size() > MAX_STORES) {
            throw new InvalidRequestParameterException("storeIds", "size", "100 boutiques au plus");
        }
        Set<UUID> active = new HashSet<>();
        for (StoreView s : stores.findAllActive(schema)) {
            active.add(s.id());
        }
        if (!active.containsAll(distinct)) {
            throw new InvalidRequestParameterException("storeIds", "unknown", StoreQueryPort.UNKNOWN_OR_INACTIVE_STORE);
        }
        return List.copyOf(distinct);
    }

    static List<VariableMapping> variables(List<VariableInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        List<VariableMapping> out = new ArrayList<>();
        for (VariableInput in : inputs) {
            if (in == null) {
                throw new InvalidRequestParameterException("variables", "source",
                        "Chaque variable doit avoir une source : texte fixe, nom du client ou nom de la boutique");
            }
            VariableSource source = VariableSource.parse(in.source()).orElseThrow(() ->
                    new InvalidRequestParameterException("variables", "source",
                            "Chaque variable doit avoir une source : texte fixe, nom du client ou nom de la boutique"));
            String value = blankToNull(in.value());
            if (value != null && value.length() > VariableMapping.MAX_VALUE) {
                throw new InvalidRequestParameterException("variables", "length",
                        "Une variable ne doit pas dépasser 1024 caractères");
            }
            if (source == VariableSource.STATIC && value == null) {
                throw new InvalidRequestParameterException("variables", "value", "Renseignez le texte de chaque variable fixe");
            }
            out.add(new VariableMapping(source, value));
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** The store name of a legacy client without a store: the company's default store. */
    private String defaultStoreName(String schema, Map<UUID, String> known) {
        return stores.defaultStore(schema).map(id -> known.containsKey(id) ? known.get(id)
                : stores.namesByIds(schema, List.of(id)).get(id)).filter(Objects::nonNull).orElse(null);
    }

    private void publish(UUID actorId, String schema, Campaign c, WhatsAppCampaignChangedEvent.Change change,
                         String detail) {
        events.publishEvent(new WhatsAppCampaignChangedEvent(actorId, schema, c.id(), c.name(), change, detail, now()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String bounded(String v) {
        return v == null ? null : v.length() > 200 ? v.substring(0, 200) : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
