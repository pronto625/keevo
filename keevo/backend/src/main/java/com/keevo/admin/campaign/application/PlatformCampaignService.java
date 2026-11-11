package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.AudienceMember;
import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.ChannelStats;
import com.keevo.admin.campaign.domain.model.HistoryEntry;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.model.PlatformCampaignStatus;
import com.keevo.admin.campaign.domain.model.Segment;
import com.keevo.admin.campaign.domain.model.WhatsAppContent;
import com.keevo.admin.campaign.domain.port.out.PlatformAudiencePort;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.notification.PlatformWhatsApp;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.store.api.StoreCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Story 21.6 — the super-admin's platform campaigns (port of Bref {@code CampaignService} + {@code TargetingService},
 * 20.13 route K1): CRUD while DRAFT / SCHEDULED, audience preview per channel, send now or at {@code scheduledAt},
 * cancel a scheduled one, history. Starting the send freezes the audience (owners of the segment's companies, one per
 * person; per channel: everyone for push, a phone for WhatsApp / SMS, an email for email) in one transaction with the
 * SENDING transition; {@link PlatformCampaignDispatcher} sends it in batches. Every change is audited
 * ({@code platform_campaign_events}, « [ADMIN AUDIT] » log line).
 */
@Service
public class PlatformCampaignService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCampaignService.class);

    public static final int MAX_RECIPIENTS = 50_000;
    public static final int PAGE = 50;
    static final Duration MAX_SCHEDULE = Duration.ofDays(365);
    static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");
    static final Set<String> HEADERS = Set.of("NONE", "TEXT", "IMAGE", "VIDEO", "DOCUMENT");
    static final Set<String> MEDIA_HEADERS = Set.of("IMAGE", "VIDEO", "DOCUMENT");
    static final Set<String> SOURCES = Set.of("STATIC", "FIRST_NAME", "LAST_NAME", "FULL_NAME", "COMPANY_NAME");

    /** What the super-admin edits ({@code channels} by name). */
    public record Draft(String name, String description, List<String> channels, String title, String message,
                        Segment segment, WhatsAppContent whatsapp, Instant scheduledAt) {
    }

    /** Which fields a PATCH carries. */
    public record Presence(boolean name, boolean description, boolean channels, boolean title, boolean message,
                           boolean segment, boolean whatsapp, boolean scheduledAt) {
    }

    /** Who the segment reaches now, per channel (owners with a phone / email; WhatsApp without STOP). */
    public record Preview(int owners, Map<Channel, Integer> byChannel, boolean overLimit) {
    }

    /** A campaign with its counters per channel and, for the detail, its history. */
    public record Detail(PlatformCampaign campaign, List<ChannelStats> stats, List<HistoryEntry> history) {
    }

    private final PlatformCampaignRepository campaigns;
    private final PlatformAudiencePort audience;
    private final PlatformWhatsApp whatsapp;
    private final PlanCatalog plans;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PlatformCampaignService(PlatformCampaignRepository campaigns, PlatformAudiencePort audience,
                                   PlatformWhatsApp whatsapp, PlanCatalog plans,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.campaigns = campaigns;
        this.audience = audience;
        this.whatsapp = whatsapp;
        this.plans = plans;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ── reads ─────────────────────────────────────────────────────────────────────────────────────────────────────

    public List<Detail> list(Instant beforeCreatedAt, UUID beforeId) {
        List<PlatformCampaign> page = campaigns.page(beforeCreatedAt, beforeCreatedAt == null ? null : beforeId, PAGE);
        Map<UUID, List<ChannelStats>> stats = campaigns.stats(page.stream().map(PlatformCampaign::id).toList());
        return page.stream().map(c -> new Detail(c, stats.getOrDefault(c.id(), List.of()), List.of())).toList();
    }

    public Detail get(UUID id) {
        PlatformCampaign c = require(id);
        return new Detail(c, campaigns.stats(List.of(id)).getOrDefault(id, List.of()), campaigns.history(id));
    }

    /**
     * Who the segment reaches now, counted in SQL (one query). The WhatsApp count is the owners with a phone, as the
     * audience is frozen; numbers that answered STOP are skipped at send time (counted « Non envoyé »).
     */
    public Preview preview(Segment raw) {
        PlatformAudiencePort.Counts n = audience.count(segment(raw), MAX_RECIPIENTS + 1);
        boolean over = n.owners() > MAX_RECIPIENTS;
        Map<Channel, Integer> counts = new EnumMap<>(Channel.class);
        counts.put(Channel.PUSH, Math.min(n.owners(), MAX_RECIPIENTS));
        counts.put(Channel.WHATSAPP, Math.min(n.withPhone(), MAX_RECIPIENTS));
        counts.put(Channel.SMS, Math.min(n.withPhone(), MAX_RECIPIENTS));
        counts.put(Channel.EMAIL, Math.min(n.withEmail(), MAX_RECIPIENTS));
        return new Preview(Math.min(n.owners(), MAX_RECIPIENTS), counts, over);
    }

    // ── writes ────────────────────────────────────────────────────────────────────────────────────────────────────

    public Detail create(UUID actorId, Draft d) {
        Instant now = now();
        PlatformCampaign c = validated(true, new PlatformCampaign(UUID.randomUUID(), d.name(), d.description(),
                PlatformCampaignStatus.DRAFT, channels(d.channels()), d.title(), d.message(), d.segment(), d.whatsapp(),
                d.scheduledAt(), null, null, 0, actorId, now, now), now);
        tx.executeWithoutResult(s -> {
            campaigns.insert(c);
            audit(c, "CREATED", actorId, null, now);
            if (c.status() == PlatformCampaignStatus.SCHEDULED) {
                audit(c, "SCHEDULED", actorId, null, now.plusNanos(1_000)); // after « Créée » in the history
            }
        });
        return get(c.id());
    }

    public Detail update(UUID actorId, UUID id, Draft d, Presence p) {
        PlatformCampaign cur = require(id);
        if (!cur.status().editable()) {
            throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
        }
        Instant now = now();
        PlatformCampaign merged = validated(p.scheduledAt(), new PlatformCampaign(id, p.name() ? d.name() : cur.name(),
                p.description() ? d.description() : cur.description(), cur.status(),
                p.channels() ? channels(d.channels()) : cur.channels(), p.title() ? d.title() : cur.title(),
                p.message() ? d.message() : cur.message(), p.segment() ? d.segment() : cur.segment(),
                p.whatsapp() ? d.whatsapp() : cur.whatsapp(), p.scheduledAt() ? d.scheduledAt() : cur.scheduledAt(),
                null, null, 0, cur.createdBy(), cur.createdAt(), now), now);
        tx.executeWithoutResult(s -> {
            if (!campaigns.updateEditable(merged)) {
                throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
            }
            audit(merged, "UPDATED", actorId, null, now);
            if (merged.status() == PlatformCampaignStatus.SCHEDULED && !merged.scheduledAt().equals(cur.scheduledAt())) {
                audit(merged, "SCHEDULED", actorId, null, now.plusNanos(1_000)); // after « Modifiée »
            }
        });
        return get(id);
    }

    public void delete(UUID actorId, UUID id) {
        PlatformCampaign c = require(id);
        Instant now = now();
        tx.executeWithoutResult(s -> {
            if (!campaigns.deleteUnsent(id)) {
                throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
            }
            audit(c, "DELETED", actorId, null, now);
        });
    }

    public Detail cancel(UUID actorId, UUID id) {
        PlatformCampaign c = require(id);
        Instant now = now();
        tx.executeWithoutResult(s -> {
            if (!campaigns.cancel(id, now)) {
                throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
            }
            audit(c, "CANCELLED", actorId, null, now);
        });
        return get(id);
    }

    /** A due scheduled campaign that cannot leave as it is goes back to DRAFT (audited by the system). */
    public void backToDraft(PlatformCampaign c, String reason) {
        Instant now = now();
        tx.executeWithoutResult(s -> {
            if (campaigns.unschedule(c.id(), now)) {
                audit(c, "UPDATED", null, reason, now);
            }
        });
    }

    public Detail send(UUID actorId, UUID id) {
        start(id, actorId);
        return get(id);
    }

    /**
     * Freezes the audience and moves the campaign to SENDING (also used by the dispatcher for a due scheduled
     * campaign, {@code actorId} {@code null}).
     *
     * @throws DomainException {@code CAMPAIGN_NOT_FOUND} 404, {@code CAMPAIGN_ALREADY_SENT} 409,
     *                         {@code CAMPAIGN_NOT_READY} 422, {@code PLATFORM_WHATSAPP_UNAVAILABLE} 503,
     *                         {@code CAMPAIGN_NO_RECIPIENT} / {@code CAMPAIGN_TOO_MANY_RECIPIENTS} 422
     */
    public void start(UUID id, UUID actorId) {
        PlatformCampaign c = require(id);
        if (!c.status().editable()) {
            throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
        }
        String problem = readiness(c);
        if (problem != null) {
            throw DomainException.userFacing(ErrorCode.CAMPAIGN_NOT_READY, problem);
        }
        if (c.channels().contains(Channel.WHATSAPP) && !whatsapp.isConfigured()) {
            throw new DomainException(ErrorCode.PLATFORM_WHATSAPP_UNAVAILABLE);
        }
        List<AudienceMember> members = audience.audience(c.segment(), MAX_RECIPIENTS + 1);
        Map<Channel, List<AudienceMember>> byChannel = split(members, c.channels());
        int total = byChannel.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            throw new DomainException(ErrorCode.CAMPAIGN_NO_RECIPIENT);
        }
        if (members.size() > MAX_RECIPIENTS || total > MAX_RECIPIENTS) {
            throw DomainException.userFacing(ErrorCode.CAMPAIGN_TOO_MANY_RECIPIENTS,
                    "Une campagne est limitée à 50 000 envois : affinez le segment");
        }
        Instant now = now();
        String detail = byChannel.entrySet().stream().map(e -> e.getKey().label() + " : " + e.getValue().size())
                .collect(Collectors.joining(", "));
        tx.executeWithoutResult(s -> {
            if (!campaigns.start(id, total, now)) {
                throw new DomainException(ErrorCode.CAMPAIGN_ALREADY_SENT);
            }
            campaigns.insertAudience(id, byChannel);
            audit(c, "SEND_STARTED", actorId, detail, now);
        });
    }

    /** The French reason the campaign cannot leave yet, or {@code null}. */
    static String readiness(PlatformCampaign c) {
        if (c.channels().isEmpty()) {
            return "Choisissez au moins un canal";
        }
        boolean needsText = c.channels().stream().anyMatch(ch -> ch != Channel.WHATSAPP);
        if (needsText && (c.message() == null || c.message().isBlank())) {
            return "Rédigez le message de la campagne";
        }
        boolean needsTitle = c.channels().contains(Channel.PUSH) || c.channels().contains(Channel.EMAIL);
        if (needsTitle && (c.title() == null || c.title().isBlank())) {
            return "Donnez un titre à la notification et à l'e-mail";
        }
        if (c.channels().contains(Channel.WHATSAPP)) {
            WhatsAppContent w = c.whatsapp();
            if (w == null || w.templateName() == null || w.templateName().isBlank()) {
                return "Choisissez le modèle WhatsApp de la campagne";
            }
            if (MEDIA_HEADERS.contains(w.headerFormat()) && blank(w.headerMediaId()) && blank(w.headerMediaLink())) {
                return "Ce modèle a un en-tête image, vidéo ou document : joignez le fichier ou indiquez un lien https";
            }
        }
        return null;
    }

    // ── audit / helpers ───────────────────────────────────────────────────────────────────────────────────────────

    void audit(PlatformCampaign c, String action, UUID actorId, String detail, Instant at) {
        campaigns.addEvent(c.id(), c.name(), action, actorId, detail, at);
        log.info("[ADMIN AUDIT] platform campaign {} — actor={} id={} channels={}{}", action, actorId, c.id(),
                c.channels(), detail == null ? "" : " (" + detail + ")");
    }

    /** Owners per channel: push for all, WhatsApp / SMS with a phone, email with an email address. */
    static Map<Channel, List<AudienceMember>> split(List<AudienceMember> members, Collection<Channel> channels) {
        Map<Channel, List<AudienceMember>> out = new EnumMap<>(Channel.class);
        for (Channel ch : channels) {
            out.put(ch, members.stream().filter(m -> switch (ch) {
                case PUSH -> true;
                case WHATSAPP, SMS -> m.phone() != null && digits(m.phone()).length() >= 8
                        && digits(m.phone()).length() <= 15; // Meta's / the SMS providers' number lengths
                case EMAIL -> m.email() != null && m.email().contains("@");
            }).toList());
        }
        return out;
    }

    private PlatformCampaign require(UUID id) {
        return campaigns.find(id).orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND));
    }

    private PlatformCampaign validated(boolean checkDate, PlatformCampaign c, Instant now) {
        String name = c.name() == null ? "" : c.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw bad("name", "Le nom de la campagne est obligatoire (100 caractères au plus)");
        }
        String description = blank(c.description()) ? null : c.description().strip();
        if (description != null && description.length() > 500) {
            throw bad("description", "La description ne doit pas dépasser 500 caractères");
        }
        String title = blank(c.title()) ? null : c.title().strip();
        if (title != null && title.length() > 100) {
            throw bad("title", "Le titre ne doit pas dépasser 100 caractères");
        }
        String message = blank(c.message()) ? null : c.message().strip();
        int maxMessage = c.channels().contains(Channel.SMS) ? 480 : 1000;
        if (message != null && message.length() > maxMessage) {
            throw bad("message", "Le message ne doit pas dépasser " + maxMessage + " caractères"
                    + (maxMessage == 480 ? " (SMS)" : ""));
        }
        WhatsAppContent w = c.whatsapp() == null ? null : whatsapp(c.whatsapp());
        Instant scheduledAt = c.scheduledAt() == null ? null : c.scheduledAt().truncatedTo(ChronoUnit.MICROS);
        if (checkDate && scheduledAt != null
                && (!scheduledAt.isAfter(now) || scheduledAt.isAfter(now.plus(MAX_SCHEDULE)))) {
            throw bad("scheduledAt", "La date d'envoi doit être dans le futur (un an au plus)");
        }
        if (w != null && w.headerMediaId() != null && scheduledAt != null
                && scheduledAt.isAfter(now.plus(Duration.ofDays(29)))) {
            throw bad("whatsapp.headerMediaId",
                    "Un fichier joint expire après 30 jours : programmez l'envoi plus tôt ou indiquez un lien https");
        }
        return new PlatformCampaign(c.id(), name, description, scheduledAt == null ? PlatformCampaignStatus.DRAFT
                : PlatformCampaignStatus.SCHEDULED, c.channels(), title, message, segment(c.segment()), w, scheduledAt,
                null, null, 0, c.createdBy(), c.createdAt(), c.updatedAt());
    }

    static List<Channel> channels(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<Channel> out = new LinkedHashSet<>();
        for (String r : raw) {
            try {
                out.add(Channel.valueOf(r == null ? "" : r.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw bad("channels", "Canaux possibles : notification, WhatsApp, SMS, e-mail");
            }
        }
        return List.copyOf(out);
    }

    private Segment segment(Segment raw) {
        Segment s = raw == null ? Segment.ALL : raw;
        if (s.planCodes().size() > 100 || s.countries().size() > 100 || s.activities().size() > 100
                || s.tenantIds().size() > 100) {
            throw bad("segment", "100 valeurs au plus par critère");
        }
        List<String> plansOut = new ArrayList<>();
        for (String p : s.planCodes()) {
            String code = p == null ? "" : p.strip().toUpperCase(Locale.ROOT);
            if (plans.find(code).isEmpty()) {
                throw bad("segment.planCodes", "Offre inconnue dans le segment");
            }
            plansOut.add(code);
        }
        List<String> countries = new ArrayList<>();
        for (String c : s.countries()) {
            String code = c == null ? "" : c.strip().toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z]{2}")) {
                throw bad("segment.countries", "Pays invalide (code à deux lettres, ex. CM)");
            }
            countries.add(code);
        }
        Set<String> keys = Arrays.stream(StoreCategory.values()).map(StoreCategory::key).collect(Collectors.toSet());
        List<String> activities = new ArrayList<>();
        for (String a : s.activities()) {
            String key = a == null ? "" : a.strip().toLowerCase(Locale.ROOT);
            if (!keys.contains(key)) {
                throw bad("segment.activities", "Activité inconnue dans le segment");
            }
            activities.add(key);
        }
        List<UUID> tenants = s.tenantIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        return new Segment(dedupe(plansOut), dedupe(countries), dedupe(activities), tenants);
    }

    private static WhatsAppContent whatsapp(WhatsAppContent w) {
        String name = w.templateName() == null ? "" : w.templateName().strip();
        if (!name.matches("[a-z0-9_]{1,512}")) {
            throw bad("whatsapp.templateName", "Nom de modèle invalide");
        }
        String language = w.language() == null ? "" : w.language().strip();
        if (!language.matches("[a-z]{2}(_[A-Z]{2})?")) {
            throw bad("whatsapp.language", "Langue du modèle invalide (ex. fr, en_US)");
        }
        String body = w.bodyText() == null ? "" : w.bodyText();
        if (body.isBlank() || body.length() > 1024) {
            throw bad("whatsapp.bodyText", "Le texte du modèle est obligatoire (1024 caractères au plus)");
        }
        String header = w.headerFormat() == null || w.headerFormat().isBlank() ? "NONE"
                : w.headerFormat().strip().toUpperCase(Locale.ROOT);
        if (!HEADERS.contains(header)) {
            throw bad("whatsapp.headerFormat", "Format d'en-tête invalide");
        }
        String mediaId = blank(w.headerMediaId()) ? null : w.headerMediaId().strip();
        String link = blank(w.headerMediaLink()) ? null : w.headerMediaLink().strip();
        if (!MEDIA_HEADERS.contains(header) && (mediaId != null || link != null)) {
            throw bad("whatsapp.headerMedia", "Ce modèle n'a pas d'en-tête image, vidéo ou document");
        }
        if (mediaId != null && link != null) {
            throw bad("whatsapp.headerMedia", "Joignez le fichier ou indiquez un lien, pas les deux");
        }
        if (mediaId != null && (mediaId.length() > 128 || !mediaId.matches("[A-Za-z0-9._:-]+"))) {
            throw bad("whatsapp.headerMediaId", "Fichier joint invalide : déposez-le de nouveau");
        }
        if (link != null && (link.length() > 2048 || !link.toLowerCase(Locale.ROOT).startsWith("https://")
                || link.chars().anyMatch(Character::isWhitespace))) {
            throw bad("whatsapp.headerMediaLink", "Le lien du média doit commencer par https:// (2048 caractères au plus)");
        }
        int expected = variableCount(body);
        if (w.variables().size() != expected) {
            throw bad("whatsapp.variables", "Ce modèle attend " + expected + (expected > 1 ? " variables" : " variable"));
        }
        List<WhatsAppContent.Variable> variables = new ArrayList<>();
        for (WhatsAppContent.Variable v : w.variables()) {
            String source = source(v == null ? null : v.source());
            String value = v == null || blank(v.value()) ? null : v.value().strip();
            if (value != null && value.length() > 1024) {
                throw bad("whatsapp.variables", "Une variable ne doit pas dépasser 1024 caractères");
            }
            if ("STATIC".equals(source) && value == null) {
                throw bad("whatsapp.variables", "Renseignez le texte de chaque variable fixe");
            }
            variables.add(new WhatsAppContent.Variable(source, value));
        }
        return new WhatsAppContent(name, language, body, header, mediaId, link, variables);
    }

    /** A variable source, Bref's spellings accepted (firstName, lastName, fullName, shopName, static). */
    static String source(String raw) {
        String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replace("_", "");
        return switch (s) {
            case "STATIC" -> "STATIC";
            case "FIRSTNAME" -> "FIRST_NAME";
            case "LASTNAME" -> "LAST_NAME";
            case "FULLNAME" -> "FULL_NAME";
            case "COMPANYNAME", "SHOPNAME", "STORENAME" -> "COMPANY_NAME";
            default -> throw bad("whatsapp.variables",
                    "Chaque variable doit avoir une source : texte fixe, prénom, nom, nom complet ou entreprise");
        };
    }

    /** The distinct {@code {{x}}} variables of a template body (Meta's rule, as whatsapp-cloud's TemplateText). */
    static int variableCount(String body) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(body == null ? "" : body);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys.size();
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static InvalidRequestParameterException bad(String field, String message) {
        return new InvalidRequestParameterException(field, "invalid", message);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
