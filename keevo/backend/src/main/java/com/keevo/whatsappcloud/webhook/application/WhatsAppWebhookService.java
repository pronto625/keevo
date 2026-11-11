package com.keevo.whatsappcloud.webhook.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.crm.api.ClientCommandPort;
import com.keevo.shared.event.catalog.campaign.PlatformWhatsAppStatusesEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.whatsappcloud.account.domain.model.RoutedAccount;
import com.keevo.whatsappcloud.account.domain.port.out.WhatsAppAccountRepository;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignRepository;
import com.keevo.whatsappcloud.config.PlatformNumberSettings;
import com.keevo.whatsappcloud.config.WhatsAppCloudSettings;
import com.keevo.whatsappcloud.consent.KeywordConsent;
import com.keevo.whatsappcloud.consent.KeywordReplier;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.inbox.domain.model.ChatMessage;
import com.keevo.whatsappcloud.inbox.domain.model.ContactPhone;
import com.keevo.whatsappcloud.inbox.domain.model.ConversationDelta;
import com.keevo.whatsappcloud.inbox.domain.model.StatusChange;
import com.keevo.whatsappcloud.inbox.domain.model.StatusUpdate;
import com.keevo.whatsappcloud.inbox.domain.port.out.WhatsAppInboxRepository;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
import com.keevo.whatsappcloud.platform.domain.port.out.PlatformOptOutRepository;
import com.keevo.whatsappcloud.template.application.WhatsAppTemplateService;
import com.keevo.whatsappcloud.template.domain.model.TemplateReview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.5 — Meta's single webhook of the Keevo app (port of Bref {@code WhatsAppController.receiveEvent} +
 * {@code handleIncomingMessage} / {@code handleStatusUpdate}), routed to the companies:
 * <ol>
 *   <li>the signature {@code X-Hub-Signature-256} over the raw body must match the app secret (else REJECTED, nothing
 *       read);</li>
 *   <li>messages and delivery statuses are grouped by {@code metadata.phone_number_id}; every number of the delivery
 *       is resolved in one indexed lookup; template reviews ({@code message_template_status_update}) by the WABA id of
 *       the entry. A number / WABA no live company has connected (platform number, owner still on api-bref, deleted
 *       company) is ignored;</li>
 *   <li>per company whose module is enabled: its tables ensured, then one transaction with one batched insert of the
 *       messages ({@code ON CONFLICT (wa_message_id) DO NOTHING}: Meta delivering again inserts nothing), one batched
 *       conversation upsert per contact for the messages really inserted, one set-based status update (never going
 *       back);</li>
 *   <li>after commit, one push per contact with new messages ({@link InboundMessageNotifier}).</li>
 * </ol>
 * Story 21.6: the platform number ({@link PlatformNumberSettings}) is routed to the platform inbox
 * ({@link WhatsAppInboxRepository#PLATFORM}, super-admin, no push); a STOP / START answer ({@link KeywordConsent}) is
 * applied in the same transaction as its message (company number → the clients' opt-out through crm; platform number
 * → the platform opt-out list), then confirmed once per minute ({@link KeywordReplier}); statuses that moved on
 * campaign messages move the campaign counters (company: same transaction; platform: {@link
 * PlatformWhatsAppStatusesEvent} to the platform campaigns).
 * One company failing never stops the others; the answer is then RETRY so Meta delivers again (what was stored is not
 * stored twice). Cost per delivery of M messages / S statuses / R reviews for T companies: 1 HMAC, ≤ 2 routing
 * lookups, per company 1 cached entitlement + 1 warm provisioning check + 1 insert batch + 1 upsert batch + 1 status
 * update (+ 1 review update); no per-message query.
 */
@Service
public class WhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Result { ACCEPTED, IGNORED, REJECTED, RETRY }

    record Inbound(String phoneNumberId, String from, String id, Instant at, String type, String body,
                   String contactName) {
    }

    record Status(String phoneNumberId, String id, String status) {
    }

    record Review(String wabaId, TemplateReview review) {
    }

    record Parsed(List<Inbound> messages, List<Status> statuses, List<Review> reviews) {
    }

    private final WhatsAppCloudSettings settings;
    private final WhatsAppAccountRepository accounts;
    private final WhatsAppInboxRepository inbox;
    private final WhatsAppTemplateService templates;
    private final EntitlementService entitlements;
    private final ModuleProvisioner provisioner;
    private final InboundMessageNotifier notifier;
    private final PlatformNumberSettings platform;
    private final ClientCommandPort clients;
    private final PlatformOptOutRepository platformOptOuts;
    private final KeywordReplier replier;
    private final CampaignRepository campaigns;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WhatsAppWebhookService(WhatsAppCloudSettings settings, WhatsAppAccountRepository accounts,
                                  WhatsAppInboxRepository inbox, WhatsAppTemplateService templates,
                                  EntitlementService entitlements, ModuleProvisioner provisioner,
                                  InboundMessageNotifier notifier, PlatformNumberSettings platform,
                                  ClientCommandPort clients, PlatformOptOutRepository platformOptOuts,
                                  KeywordReplier replier, CampaignRepository campaigns,
                                  ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.settings = settings;
        this.accounts = accounts;
        this.inbox = inbox;
        this.templates = templates;
        this.entitlements = entitlements;
        this.provisioner = provisioner;
        this.notifier = notifier;
        this.platform = platform;
        this.clients = clients;
        this.platformOptOuts = platformOptOuts;
        this.replier = replier;
        this.campaigns = campaigns;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What one company's (or the platform's) delivery produced, to hand over after commit. */
    record Stored(List<InboundMessageNotifier.Notice> notices, List<KeywordReplier.Reply> replies) {
    }

    /** The subscription check of {@code GET}: the challenge to echo, or {@code null} when refused. */
    public String verify(String mode, String token, String challenge) {
        if ("subscribe".equals(mode) && MetaWebhookSignature.tokenMatches(token, settings.verifyToken())
                && challenge != null && challenge.length() <= 256) {
            return challenge;
        }
        return null;
    }

    public Result handle(byte[] rawBody, String signature) {
        if (!MetaWebhookSignature.isValid(settings.appSecret(), rawBody, signature)) {
            log.warn("[WhatsApp Cloud] webhook refused: missing or invalid signature");
            return Result.REJECTED;
        }
        Parsed parsed;
        try {
            parsed = parse(JSON.readTree(rawBody), clock.instant());
        } catch (IOException e) {
            log.warn("[WhatsApp Cloud] webhook body is not JSON: ignored");
            return Result.IGNORED;
        }
        if (parsed == null) {
            return Result.IGNORED;
        }
        boolean failed = processMessages(parsed);
        failed |= processReviews(parsed.reviews());
        return failed ? Result.RETRY : Result.ACCEPTED;
    }

    // ── messages and statuses ─────────────────────────────────────────────────────────────────────

    private boolean processMessages(Parsed parsed) {
        Set<String> numbers = new LinkedHashSet<>();
        parsed.messages().forEach(m -> numbers.add(m.phoneNumberId()));
        parsed.statuses().forEach(s -> numbers.add(s.phoneNumberId()));
        if (numbers.isEmpty()) {
            return false;
        }
        Map<String, String> schemaByNumber = new HashMap<>();
        for (RoutedAccount r : accounts.routeByPhoneNumberIds(numbers)) {
            schemaByNumber.put(r.routingKey(), r.tenantSchema());
        }
        numbers.stream().filter(platform::isPlatformNumber).findFirst()
                .ifPresent(n -> schemaByNumber.put(n, WhatsAppInboxRepository.PLATFORM));
        Map<String, List<Inbound>> messagesBySchema = new LinkedHashMap<>();
        Map<String, List<Status>> statusesBySchema = new LinkedHashMap<>();
        int unknown = 0;
        for (Inbound m : parsed.messages()) {
            String schema = schemaByNumber.get(m.phoneNumberId());
            if (schema == null) {
                unknown++;
            } else {
                messagesBySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(m);
            }
        }
        for (Status s : parsed.statuses()) {
            String schema = schemaByNumber.get(s.phoneNumberId());
            if (schema != null) {
                statusesBySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(s);
            }
        }
        if (unknown > 0) {
            log.info("[WhatsApp Cloud] {} message(s) for a number no company has connected: ignored", unknown);
        }
        Set<String> schemas = new LinkedHashSet<>(messagesBySchema.keySet());
        schemas.addAll(statusesBySchema.keySet());
        boolean failed = false;
        List<InboundMessageNotifier.Notice> notices = new ArrayList<>();
        List<KeywordReplier.Reply> replies = new ArrayList<>();
        for (String schema : schemas) {
            try {
                if (!usable(schema)) {
                    continue;
                }
                Stored stored = store(schema, messagesBySchema.getOrDefault(schema, List.of()),
                        statusesBySchema.getOrDefault(schema, List.of()));
                notices.addAll(stored.notices());
                replies.addAll(stored.replies());
            } catch (RuntimeException e) {
                failed = true;
                log.error("[WhatsApp Cloud] webhook delivery not stored for tenant {} ({})", schema,
                        e.getClass().getSimpleName(), e);
            }
        }
        notifier.dispatch(notices);
        replier.dispatch(replies);
        return failed;
    }

    /** Module enabled for the company (else its deliveries are ignored), tables ensured; the platform inbox always. */
    private boolean usable(String schema) {
        if (WhatsAppInboxRepository.PLATFORM.equals(schema)) {
            return true;
        }
        if (!entitlements.isEnabled(schema, WhatsAppCloudModule.ID)) {
            log.info("[WhatsApp Cloud] delivery for tenant {} ignored: module not enabled", schema);
            return false;
        }
        provisioner.ensureProvisioned(schema, WhatsAppCloudModule.ID);
        return true;
    }

    /**
     * One company's (or the platform's) messages and statuses in one transaction: the messages inserted now, their
     * conversations, the STOP / START answers among them, the statuses (and the campaign counters they move). Returns
     * the pushes and the keyword confirmations to send after commit.
     */
    Stored store(String schema, List<Inbound> received, List<Status> statuses) {
        boolean isPlatform = WhatsAppInboxRepository.PLATFORM.equals(schema);
        Map<String, Inbound> byId = new LinkedHashMap<>();
        received.forEach(m -> byId.putIfAbsent(m.id(), m)); // the same id twice in one delivery: once
        List<Inbound> unique = new ArrayList<>(byId.values());
        List<ChatMessage> rows = unique.stream().map(m -> new ChatMessage(UUID.randomUUID(), m.from(),
                ChatMessage.Direction.IN, m.type(), m.body(), m.id(), null, null, null, m.at())).toList();
        List<StatusUpdate> updates = collapse(statuses);
        String previous = TenantContext.getCurrentTenant();
        if (!isPlatform) {
            TenantContext.setCurrentTenant(schema); // crm's port runs in the company's schema
        }
        try {
            return tx.execute(s -> {
                boolean[] inserted = inbox.insertAll(schema, rows);
                Map<String, ConversationAgg> perContact = new LinkedHashMap<>();
                Map<String, Inbound> lastKeyword = new LinkedHashMap<>();
                for (int i = 0; i < unique.size(); i++) {
                    if (inserted[i]) {
                        Inbound m = unique.get(i);
                        perContact.computeIfAbsent(m.from(), k -> new ConversationAgg()).add(m);
                        if ("text".equals(m.type()) && KeywordConsent.parse(m.body()).isPresent()) {
                            lastKeyword.merge(m.from(), m, (a, b) -> b.at().isBefore(a.at()) ? a : b);
                        }
                    }
                }
                List<ConversationDelta> deltas = new ArrayList<>();
                List<InboundMessageNotifier.Notice> notices = new ArrayList<>();
                perContact.forEach((phone, agg) -> {
                    deltas.add(new ConversationDelta(phone, agg.name, WhatsAppInboxService.preview(agg.lastPreview()),
                            agg.last.type(), ChatMessage.Direction.IN, agg.last.at(), agg.count, agg.last.at()));
                    if (!isPlatform) { // the platform number is followed from the dashboard only (Bref)
                        notices.add(new InboundMessageNotifier.Notice(schema, phone, agg.name, agg.count,
                                agg.lastPreview()));
                    }
                });
                inbox.upsertConversations(schema, deltas);
                List<KeywordReplier.Reply> replies = applyKeywords(schema, isPlatform, lastKeyword);
                List<StatusChange> changes = inbox.applyStatuses(schema, updates);
                List<StatusChange> campaignChanges = changes.stream().filter(c -> c.campaignId() != null).toList();
                if (!campaignChanges.isEmpty()) {
                    if (isPlatform) {
                        events.publishEvent(new PlatformWhatsAppStatusesEvent(campaignChanges.stream()
                                .map(c -> new PlatformWhatsAppStatusesEvent.Change(c.campaignId(), c.oldStatus(),
                                        c.newStatus())).toList()));
                    } else {
                        campaigns.applyStatusChanges(schema, campaignChanges);
                    }
                }
                return new Stored(notices, replies);
            });
        } finally {
            if (!isPlatform) {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setCurrentTenant(previous);
                }
            }
        }
    }

    /** STOP / START of each contact (its latest keyword of the delivery), applied now; the confirmations to send. */
    private List<KeywordReplier.Reply> applyKeywords(String schema, boolean isPlatform, Map<String, Inbound> last) {
        List<KeywordReplier.Reply> replies = new ArrayList<>();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        last.forEach((phone, m) -> {
            Optional<KeywordConsent.Answer> answer = KeywordConsent.parse(m.body());
            if (answer.isEmpty()) {
                return;
            }
            boolean optOut = answer.get() == KeywordConsent.Answer.OPT_OUT;
            boolean changed;
            if (isPlatform) {
                changed = optOut ? platformOptOuts.optOut(phone, now) : platformOptOuts.optIn(phone);
            } else {
                changed = clients.setMarketingOptOutByPhone(schema, "+" + phone, optOut, now) > 0;
            }
            if (!changed && !optOut) {
                return; // START from a number that was not opted out: nothing to confirm (no false « réabonné »)
            }
            log.info("[WhatsApp Cloud] {} by a contact of {}", optOut ? "STOP" : "START",
                    isPlatform ? "the platform number" : "tenant " + schema);
            replies.add(new KeywordReplier.Reply(schema, phone, answer.get()));
        });
        return replies;
    }

    /** One status per message id: the one that supersedes the others. */
    static List<StatusUpdate> collapse(List<Status> statuses) {
        Map<String, String> best = new LinkedHashMap<>();
        for (Status s : statuses) {
            best.merge(s.id(), s.status(), (a, b) -> StatusUpdate.supersedes(b, a) ? b : a);
        }
        List<StatusUpdate> updates = new ArrayList<>();
        best.forEach((id, status) -> updates.add(new StatusUpdate(id, status)));
        return updates;
    }

    /** The newest message of a contact in one delivery, the count, the latest profile name. */
    private static final class ConversationAgg {
        Inbound last;
        String name;
        int count;

        void add(Inbound m) {
            count++;
            if (last == null || !m.at().isBefore(last.at())) {
                last = m;
            }
            if (m.contactName() != null) {
                name = m.contactName();
            }
        }

        String lastPreview() {
            return last.body() != null && !last.body().isBlank() ? last.body() : label(last.type());
        }
    }

    /** What a message without text reads as in the list and the push. */
    static String label(String type) {
        return switch (type == null ? "" : type) {
            case "image" -> "Photo";
            case "video" -> "Vidéo";
            case "audio", "voice" -> "Message vocal";
            case "document" -> "Document";
            case "sticker" -> "Autocollant";
            case "location" -> "Position partagée";
            case "contacts" -> "Contact partagé";
            default -> "Message";
        };
    }

    // ── template reviews ──────────────────────────────────────────────────────────────────────────

    private boolean processReviews(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return false;
        }
        Set<String> wabas = new LinkedHashSet<>();
        reviews.forEach(r -> wabas.add(r.wabaId()));
        Map<String, List<String>> schemasByWaba = new HashMap<>();
        for (RoutedAccount r : accounts.routeByWabaIds(wabas)) {
            schemasByWaba.computeIfAbsent(r.routingKey(), k -> new ArrayList<>()).add(r.tenantSchema());
        }
        Map<String, List<TemplateReview>> bySchema = new LinkedHashMap<>();
        for (Review r : reviews) {
            for (String schema : schemasByWaba.getOrDefault(r.wabaId(), List.of())) {
                bySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(r.review());
            }
        }
        boolean failed = false;
        for (Map.Entry<String, List<TemplateReview>> e : bySchema.entrySet()) {
            try {
                if (usable(e.getKey())) {
                    templates.applyReviews(e.getKey(), e.getValue());
                }
            } catch (RuntimeException ex) {
                failed = true;
                log.error("[WhatsApp Cloud] template review not stored for tenant {} ({})", e.getKey(),
                        ex.getClass().getSimpleName());
            }
        }
        return failed;
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────────────────

    /** Meta's payload → messages, statuses, reviews; {@code null} when it is not a WhatsApp Business delivery. */
    static Parsed parse(JsonNode root, Instant now) {
        if (!"whatsapp_business_account".equals(root.path("object").asText())) {
            return null;
        }
        List<Inbound> messages = new ArrayList<>();
        List<Status> statuses = new ArrayList<>();
        List<Review> reviews = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                String field = change.path("field").asText();
                JsonNode value = change.path("value");
                if ("messages".equals(field)) {
                    String number = value.path("metadata").path("phone_number_id").asText("");
                    if (number.isEmpty()) {
                        continue;
                    }
                    Map<String, String> names = new HashMap<>();
                    for (JsonNode c : value.path("contacts")) {
                        String name = c.path("profile").path("name").asText("");
                        if (!name.isBlank()) {
                            names.put(c.path("wa_id").asText(), bounded(name.strip(), 200));
                        }
                    }
                    for (JsonNode m : value.path("messages")) {
                        String from = ContactPhone.digitsOrNull(m.path("from").asText(null));
                        String id = m.path("id").asText("");
                        if (from == null || id.isEmpty() || id.length() > 128) {
                            continue;
                        }
                        String type = m.path("type").asText("text");
                        messages.add(new Inbound(number, from, id, at(m.path("timestamp").asText(null), now),
                                storedType(type), bounded(body(m, type), 8192), names.get(m.path("from").asText())));
                    }
                    for (JsonNode st : value.path("statuses")) {
                        String status = StatusUpdate.parse(st.path("status").asText(null));
                        String id = st.path("id").asText("");
                        if (status != null && !id.isEmpty()) {
                            statuses.add(new Status(number, id, status));
                        }
                    }
                } else if ("message_template_status_update".equals(field)) {
                    String waba = entry.path("id").asText("");
                    String event = value.path("event").asText("").toUpperCase(Locale.ROOT);
                    if (waba.isEmpty() || !Set.of("APPROVED", "REJECTED", "PAUSED", "DISABLED", "PENDING")
                            .contains(event)) {
                        continue;
                    }
                    String metaId = value.path("message_template_id").asText("");
                    reviews.add(new Review(waba, WhatsAppTemplateService.review(metaId.isEmpty() ? null : metaId,
                            value.path("message_template_name").asText(null),
                            value.path("message_template_language").asText(null), event,
                            value.path("reason").asText(null))));
                }
            }
        }
        return new Parsed(messages, statuses, reviews);
    }

    /** Text, button answer, interactive reply or media caption (Bref's order); {@code null} otherwise. */
    static String body(JsonNode m, String type) {
        String[][] paths = {
                {"text", "body"}, {"button", "text"}, {"interactive", "button_reply", "title"},
                {"interactive", "list_reply", "title"}, {"image", "caption"}, {"video", "caption"},
                {"document", "caption"}, {"document", "filename"}};
        for (String[] path : paths) {
            JsonNode n = m;
            for (String p : path) {
                n = n.path(p);
            }
            if (n.isTextual() && !n.asText().isBlank()) {
                return n.asText();
            }
        }
        return null;
    }

    /** A button or interactive answer reads as text (Bref). */
    static String storedType(String type) {
        String t = type == null || type.isBlank() ? "text" : type.toLowerCase(Locale.ROOT);
        if ("button".equals(t) || "interactive".equals(t)) {
            return "text";
        }
        return t.length() > 20 ? t.substring(0, 20) : t;
    }

    /** Meta's epoch seconds, else now (never in the future beyond now). */
    static Instant at(String epochSeconds, Instant now) {
        try {
            Instant at = Instant.ofEpochSecond(Long.parseLong(epochSeconds));
            return (at.isAfter(now) ? now : at).truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException e) {
            return now.truncatedTo(ChronoUnit.MICROS);
        }
    }

    private static String bounded(String v, int max) {
        return v == null ? null : v.length() > max ? v.substring(0, max) : v;
    }
}
