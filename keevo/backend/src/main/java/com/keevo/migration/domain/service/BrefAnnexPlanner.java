package com.keevo.migration.domain.service;

import com.keevo.assistant.api.AiConversationImportPort;
import com.keevo.assistant.api.AiConversationImportPort.ImportedConversation;
import com.keevo.messaging.api.NotificationImportPort;
import com.keevo.messaging.api.NotificationImportPort.ImportedNotification;
import com.keevo.messaging.api.NotificationImportPort.ImportedWebPush;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.migration.domain.model.BrefAnnexes.AiConversation;
import com.keevo.migration.domain.model.BrefAnnexes.Campaign;
import com.keevo.migration.domain.model.BrefAnnexes.CampaignMessage;
import com.keevo.migration.domain.model.BrefAnnexes.ChatMessage;
import com.keevo.migration.domain.model.BrefAnnexes.Content;
import com.keevo.migration.domain.model.BrefAnnexes.Notification;
import com.keevo.migration.domain.model.BrefAnnexes.Targeting;
import com.keevo.migration.domain.model.BrefAnnexes.Template;
import com.keevo.migration.domain.model.BrefAnnexes.WebPushSubscription;
import com.keevo.migration.domain.model.BrefAnnexes.WhatsAppAccount;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.ImportNote.Kind;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedAccount;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedCampaign;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedMessage;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedRecipient;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedTemplate;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.Variable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.keevo.migration.domain.model.BrefAnnexes.BREF_AI_CONVERSATION;
import static com.keevo.migration.domain.model.BrefAnnexes.BREF_CAMPAIGN;
import static com.keevo.migration.domain.model.BrefAnnexes.BREF_CAMPAIGN_MESSAGE;
import static com.keevo.migration.domain.model.BrefAnnexes.BREF_CHAT_MESSAGE;
import static com.keevo.migration.domain.model.BrefAnnexes.BREF_NOTIFICATION;
import static com.keevo.migration.domain.model.BrefAnnexes.BREF_TEMPLATE;
import static com.keevo.migration.domain.model.BrefAnnexes.BREF_WEB_PUSH;
import static com.keevo.migration.domain.model.BrefAnnexes.BREF_WHATSAPP_ACCOUNT;

/**
 * Story 24.6 — the pure rules of the annex import: what each Bref row becomes in Keevo (WhatsApp Business account,
 * templates, campaigns and recipients, inbox messages, AI conversations, notifications), the notes, and the blocking
 * case. No I/O: the run's lookups come in {@link Lookup}. Bref values are kept; a value Keevo's tables cannot hold is
 * shortened (noted) or the row is left out (noted), never silently changed. Rows are planned in a deterministic order
 * (Bref creation instant, then id), so duplicates resolve the same way on every run and from both sources.
 *
 * <p>Cost: O(n log n) in the section's rows (sorting), maps for every lookup.
 */
public final class BrefAnnexPlanner {

    static final Pattern META_ID = Pattern.compile("^[0-9]{1,64}$");
    static final Pattern TEMPLATE_NAME = Pattern.compile("^[a-z0-9_]{1,512}$");
    static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(_[A-Z]{2})?$");
    static final Set<String> TEMPLATE_STATUSES = Set.of("DRAFT", "PENDING", "APPROVED", "REJECTED", "PAUSED",
            "DISABLED");
    static final Set<String> CATEGORIES = Set.of("MARKETING", "UTILITY");
    static final Set<String> HEADERS = Set.of("NONE", "TEXT", "IMAGE", "VIDEO", "DOCUMENT");
    static final String RESERVED_TEMPLATE = "bref_facture";
    static final String RESERVED_LANGUAGE = "fr";
    static final String DEFAULT_CAMPAIGN_NAME = "Campagne importée";

    private BrefAnnexPlanner() {
    }

    /**
     * The run's lookups.
     *
     * @param ownerAccount the owner's Keevo account (created or linked)
     * @param accountId    a Bref account's Keevo account, else {@code null} (not imported, or only invited)
     * @param stores       the Bref shops imported as Keevo stores (same ids)
     * @param shopNames    name of each Bref shop
     * @param customers    each Bref customer: its name and shop
     */
    public record Lookup(UUID ownerAccount, Function<UUID, UUID> accountId, Set<UUID> stores,
                         Map<UUID, String> shopNames, Map<UUID, Customer> customers) {
    }

    public record Customer(String name, UUID shopId) {
    }

    /** A row to write with its record's source and checksum, and the notes it carries once written. */
    public record Row<T>(T row, String sourceTable, UUID sourceId, UUID targetId, String checksum, List<Kind> notes) {
    }

    /**
     * The plan of one run.
     *
     * @param account      the WABA to store, or {@code null}
     * @param contactNames latest contact name per phone (digits)
     * @param skipped      notes of the rows left out (one per row)
     */
    public record Plan(Row<ImportedAccount> account, List<Row<ImportedTemplate>> templates,
                       List<Row<ImportedCampaign>> campaigns, List<Row<ImportedRecipient>> recipients,
                       List<Row<ImportedMessage>> messages, Map<String, String> contactNames,
                       List<Row<ImportedConversation>> conversations, List<Row<ImportedNotification>> notifications,
                       List<Row<ImportedWebPush>> webPush, List<Kind> skipped) {
    }

    /**
     * The blocking case: a connected, readable WABA while the server cannot encrypt tokens (nothing would keep it
     * safely: fix the key, re-run).
     */
    public static List<BlockedCase> validate(BrefAnnexes section, boolean canStoreTokens) {
        WhatsAppAccount a = section.whatsappAccount();
        if (a != null && !canStoreTokens && a.connected() && readable(a)) {
            return List.of(BlockedCase.of(BlockedCase.Kind.ANNEX_INVALID,
                    "la clé de chiffrement des secrets n'est pas configurée sur le serveur", BREF_WHATSAPP_ACCOUNT,
                    a.id()));
        }
        return List.of();
    }

    public static Plan plan(BrefAnnexes section, Lookup lookup) {
        List<Kind> skipped = new ArrayList<>();
        Row<ImportedAccount> account = account(section.whatsappAccount(), lookup, skipped);
        List<Row<ImportedTemplate>> templates = new ArrayList<>();
        Map<String, UUID> templateIds = new HashMap<>();
        for (Template t : sorted(section.templates(), Template::createdAt, Template::id)) {
            Row<ImportedTemplate> row = template(t, lookup, skipped);
            if (row != null) {
                templates.add(row);
                templateIds.put(row.row().name() + "|" + row.row().language(), row.row().id());
            }
        }
        List<Row<ImportedCampaign>> campaigns = new ArrayList<>();
        Set<UUID> campaignIds = new HashSet<>();
        for (Campaign c : sorted(section.campaigns(), Campaign::createdAt, Campaign::id)) {
            Row<ImportedCampaign> row = campaign(c, lookup, templateIds);
            campaigns.add(row);
            campaignIds.add(c.id());
        }
        List<Row<ImportedRecipient>> recipients = recipients(section.campaignMessages(), campaignIds, lookup,
                skipped);
        Map<String, String> contactNames = new HashMap<>();
        List<Row<ImportedMessage>> messages = messages(section.chatMessages(), lookup, contactNames, skipped);
        List<Row<ImportedConversation>> conversations = conversations(section.aiConversations(), lookup, skipped);
        List<Row<ImportedNotification>> notifications = notifications(section.notifications(), lookup, skipped);
        List<Row<ImportedWebPush>> webPush = webPush(section.webPushSubscriptions(), lookup, skipped);
        return new Plan(account, templates, campaigns, recipients, messages, contactNames, conversations,
                notifications, webPush, skipped);
    }

    // ── account ──────────────────────────────────────────────────────────────

    private static boolean readable(WhatsAppAccount a) {
        String waba = BrefMappings.trimmed(a.wabaId());
        String number = BrefMappings.trimmed(a.phoneNumberId());
        String token = BrefMappings.trimmed(a.accessToken());
        return waba != null && number != null && token != null && META_ID.matcher(waba).matches()
                && META_ID.matcher(number).matches();
    }

    private static Row<ImportedAccount> account(WhatsAppAccount a, Lookup lookup, List<Kind> skipped) {
        if (a == null) {
            return null;
        }
        if (!a.connected()) {
            skipped.add(Kind.WHATSAPP_ACCOUNT_DISCONNECTED);
            return null;
        }
        if (!readable(a)) {
            skipped.add(Kind.WHATSAPP_ACCOUNT_UNREADABLE);
            return null;
        }
        List<Kind> notes = new ArrayList<>();
        boolean[] cut = {false};
        ImportedAccount row = new ImportedAccount(a.wabaId().strip(), a.phoneNumberId().strip(),
                cut(BrefMappings.trimmed(a.displayPhoneNumber()), 32, cut),
                cut(BrefMappings.trimmed(a.verifiedName()), 200, cut), a.accessToken().strip(), lookup.ownerAccount(),
                a.createdAt());
        if (cut[0]) {
            notes.add(Kind.WHATSAPP_FIELD_SHORTENED);
        }
        return new Row<>(row, BREF_WHATSAPP_ACCOUNT, a.id(), null, Canonical.sha256(a.canonical()), notes);
    }

    // ── templates ────────────────────────────────────────────────────────────

    private static Row<ImportedTemplate> template(Template t, Lookup lookup, List<Kind> skipped) {
        String name = BrefMappings.trimmed(t.name());
        String language = BrefMappings.trimmed(t.language());
        String category = upper(t.category());
        String header = upper(t.headerFormat());
        header = header == null ? "NONE" : header;
        String body = BrefMappings.trimmed(t.bodyText()) == null ? null : t.bodyText();
        if (name == null || !TEMPLATE_NAME.matcher(name).matches() || language == null
                || !LANGUAGE.matcher(language).matches() || !CATEGORIES.contains(category) || !HEADERS.contains(header)
                || body == null || length(body) > 1024 || length(t.headerText()) > 60 || length(t.footerText()) > 60) {
            skipped.add(Kind.WHATSAPP_TEMPLATE_UNREADABLE);
            return null;
        }
        if (RESERVED_TEMPLATE.equals(name) && RESERVED_LANGUAGE.equals(language)) {
            skipped.add(Kind.WHATSAPP_TEMPLATE_RESERVED);
            return null;
        }
        String status = upper(t.status());
        if (!TEMPLATE_STATUSES.contains(status)) {
            status = "PENDING"; // Meta's in-between states (IN_APPEAL…), as 21.5 normalises them
        }
        List<Kind> notes = new ArrayList<>();
        boolean[] cut = {false};
        String metaId = BrefMappings.trimmed(t.metaTemplateId());
        ImportedTemplate row = new ImportedTemplate(t.id(), name, language, category, header,
                "TEXT".equals(header) ? BrefMappings.trimmed(t.headerText()) : null, body, t.bodyExamples(),
                BrefMappings.trimmed(t.footerText()), status, cut(BrefMappings.trimmed(t.rejectedReason()), 200, cut),
                cut(BrefMappings.trimmed(t.submitError()), 500, cut), metaId != null && length(metaId) <= 64 ? metaId
                        : null, lookup.ownerAccount(), t.createdAt(), orCreated(t.updatedAt(), t.createdAt()));
        if (cut[0]) {
            notes.add(Kind.WHATSAPP_FIELD_SHORTENED);
        }
        if ("DRAFT".equals(status) && BrefMappings.trimmed(t.headerMediaUrl()) != null
                && !"NONE".equals(header) && !"TEXT".equals(header)) {
            notes.add(Kind.WHATSAPP_TEMPLATE_MEDIA_DROPPED);
        }
        return new Row<>(row, BREF_TEMPLATE, t.id(), t.id(), Canonical.sha256(t.canonical()), notes);
    }

    // ── campaigns ────────────────────────────────────────────────────────────

    private static Row<ImportedCampaign> campaign(Campaign c, Lookup lookup, Map<String, UUID> templateIds) {
        List<Kind> notes = new ArrayList<>();
        String bref = c.status() == null ? "" : c.status().strip().toLowerCase(Locale.ROOT);
        String status;
        switch (bref) {
            case "draft" -> status = "DRAFT";
            case "scheduled" -> {
                status = "DRAFT";
                notes.add(Kind.CAMPAIGN_SCHEDULE_DROPPED);
            }
            case "sending" -> {
                status = "SENT";
                notes.add(Kind.CAMPAIGN_SENDING_CLOSED);
            }
            case "sent" -> status = "SENT";
            case "cancelled" -> status = "CANCELLED";
            default -> {
                status = "DRAFT";
                notes.add(Kind.CAMPAIGN_STATUS_UNKNOWN);
            }
        }
        boolean[] cut = {false};
        boolean[] dropped = {false};
        String name = BrefMappings.trimmed(c.name());
        name = name == null ? DEFAULT_CAMPAIGN_NAME : cut(name, 100, cut);
        String description = cut(BrefMappings.trimmed(c.description()), 500, cut);

        Targeting t = c.targeting();
        dropped[0] |= t.unreadable();
        List<UUID> stores = new ArrayList<>();
        for (UUID shop : t.shopIds()) {
            if (lookup.stores().contains(shop) && !stores.contains(shop)) {
                stores.add(shop);
            } else if (!lookup.stores().contains(shop)) {
                dropped[0] = true;
            }
        }
        Integer inactive = intIn(t.inactiveForDays(), 1, 3650, dropped);
        Integer within = intIn(t.purchasedWithinDays(), 1, 3650, dropped);
        Long spentMin = longIn(t.totalSpentMin(), 0, 1_000_000_000_000L, dropped);
        Long spentMax = longIn(t.totalSpentMax(), 0, 1_000_000_000_000L, dropped);
        Integer invMin = intIn(t.totalInvoicesMin(), 0, 10_000_000, dropped);
        Integer invMax = intIn(t.totalInvoicesMax(), 0, 10_000_000, dropped);

        Content w = c.content();
        UUID templateId = null;
        String templateName = null;
        String templateLanguage = null;
        String templateBody = null;
        String templateHeader = null;
        String link = null;
        List<Variable> variables = new ArrayList<>();
        if (w != null) {
            templateName = BrefMappings.trimmed(w.templateName());
            templateLanguage = BrefMappings.trimmed(w.language());
            if (templateName != null && length(templateName) > 512 || templateLanguage != null
                    && length(templateLanguage) > 10) {
                templateName = null;
                templateLanguage = null;
                dropped[0] = true;
            }
            templateId = templateName == null ? null : templateIds.get(templateName + "|" + templateLanguage);
            templateBody = cut(w.bodyText() == null || w.bodyText().isBlank() ? null : w.bodyText(), 1024, cut);
            String header = upper(w.headerFormat());
            templateHeader = header != null && HEADERS.contains(header) ? header : null;
            if (header != null && templateHeader == null) {
                dropped[0] = true;
            }
            String url = BrefMappings.trimmed(w.headerMediaUrl());
            if (url != null) {
                if (url.startsWith("https://") && length(url) <= 2048) {
                    link = url;
                } else {
                    dropped[0] = true;
                }
            }
            for (BrefAnnexes.Variable v : w.variables()) {
                String source = switch (v.source() == null ? "" : v.source().strip()) {
                    case "customerName" -> "CLIENT_NAME";
                    case "shopName" -> "STORE_NAME";
                    case "static" -> "STATIC";
                    default -> {
                        dropped[0] = true;
                        yield "STATIC";
                    }
                };
                variables.add(new Variable(source, cut(v.value(), 1024, cut)));
            }
        }
        if (cut[0]) {
            notes.add(Kind.WHATSAPP_FIELD_SHORTENED);
        }
        if (dropped[0]) {
            notes.add(Kind.CAMPAIGN_FIELD_DROPPED);
        }
        UUID author = c.createdById() == null ? null : lookup.accountId().apply(c.createdById());
        Instant sentAt = "SENT".equals(status) ? c.sentAt() : null;
        ImportedCampaign row = new ImportedCampaign(c.id(), name, description, status, stores, templateId, variables,
                link, templateName, templateLanguage, templateBody, templateHeader, sentAt, sentAt,
                Math.max(0, c.targetedCount()), Math.max(0, c.sentCount()), Math.max(0, c.deliveredCount()),
                Math.max(0, c.readCount()), Math.max(0, c.failedCount()), inactive, within, spentMin, spentMax,
                invMin, invMax, author != null ? author : lookup.ownerAccount(), c.createdAt(),
                orCreated(c.updatedAt(), c.createdAt()));
        return new Row<>(row, BREF_CAMPAIGN, c.id(), c.id(), Canonical.sha256(c.canonical()), notes);
    }

    /** One recipient per campaign and phone: the most recent Bref message wins (Bref resent to a number). */
    private static List<Row<ImportedRecipient>> recipients(List<CampaignMessage> messages, Set<UUID> campaigns,
                                                           Lookup lookup, List<Kind> skipped) {
        Map<String, CampaignMessage> latest = new LinkedHashMap<>();
        Map<String, String> phones = new HashMap<>();
        for (CampaignMessage m : sorted(messages, CampaignMessage::createdAt, CampaignMessage::id)) {
            if (m.customerCampaignId() == null || !campaigns.contains(m.customerCampaignId())) {
                continue; // not one of the owner's campaigns
            }
            String phone = digits(m.phone(), 6, 20);
            if (phone == null || m.customerId() == null) {
                skipped.add(Kind.CAMPAIGN_RECIPIENT_UNREADABLE);
                continue;
            }
            String key = m.customerCampaignId() + ":" + phone;
            if (latest.put(key, m) != null) {
                skipped.add(Kind.CAMPAIGN_RECIPIENT_DUPLICATE);
            }
            phones.put(key, phone);
        }
        List<Row<ImportedRecipient>> rows = new ArrayList<>();
        latest.forEach((key, m) -> {
            Customer customer = lookup.customers().get(m.customerId());
            String status = switch (m.status() == null ? "" : m.status().strip().toLowerCase(Locale.ROOT)) {
                case "sent", "delivered", "read" -> "SENT";
                case "failed" -> "FAILED";
                default -> "UNCERTAIN";
            };
            boolean[] cut = {false};
            String waId = BrefMappings.trimmed(m.waMessageId());
            ImportedRecipient row = new ImportedRecipient(m.customerCampaignId(), phones.get(key), m.customerId(),
                    customer == null ? null : cut(BrefMappings.trimmed(customer.name()), 200, cut),
                    customer == null ? null : cut(BrefMappings.trimmed(lookup.shopNames().get(customer.shopId())),
                            200, cut), status, waId != null && length(waId) <= 128 ? waId : null,
                    cut(BrefMappings.trimmed(m.error()), 200, cut), orCreated(m.updatedAt(), m.createdAt()));
            rows.add(new Row<>(row, BREF_CAMPAIGN_MESSAGE, m.id(), m.id(), Canonical.sha256(m.canonical()),
                    cut[0] ? List.of(Kind.WHATSAPP_FIELD_SHORTENED) : List.of()));
        });
        return rows;
    }

    // ── inbox ────────────────────────────────────────────────────────────────

    private static List<Row<ImportedMessage>> messages(List<ChatMessage> chat, Lookup lookup,
                                                       Map<String, String> contactNames, List<Kind> skipped) {
        List<Row<ImportedMessage>> rows = new ArrayList<>();
        Set<String> waIds = new HashSet<>();
        for (ChatMessage m : sorted(chat, ChatMessage::createdAt, ChatMessage::id)) {
            String phone = digits(m.contactPhone(), 6, 20);
            String direction = m.direction() == null ? null : switch (m.direction().strip().toLowerCase(Locale.ROOT)) {
                case "in" -> "IN";
                case "out" -> "OUT";
                default -> null;
            };
            if (phone == null || direction == null) {
                skipped.add(Kind.CHAT_MESSAGE_UNREADABLE);
                continue;
            }
            String waId = BrefMappings.trimmed(m.waMessageId());
            if (waId == null || length(waId) > 128) {
                waId = "bref:" + m.id(); // never matched by a Meta webhook
            }
            if (!waIds.add(waId)) {
                skipped.add(Kind.CHAT_MESSAGE_DUPLICATE);
                continue;
            }
            boolean[] cut = {false};
            String type = BrefMappings.trimmed(m.type());
            type = type == null ? "text" : cut(type, 20, cut);
            String status = null;
            if ("OUT".equals(direction) && m.status() != null) {
                String s = m.status().strip().toUpperCase(Locale.ROOT);
                status = Set.of("SENT", "DELIVERED", "READ", "FAILED").contains(s) ? s : null;
            }
            String name = BrefMappings.trimmed(m.contactName());
            if (name != null) {
                contactNames.put(phone, cut(name, 200, new boolean[1])); // sorted: the latest name wins
            }
            ImportedMessage row = new ImportedMessage(m.id(), phone, direction, type,
                    m.body() == null ? null : cut(m.body(), 8192, cut), waId, status,
                    "IN".equals(direction) ? m.readAt() : null,
                    m.sentById() == null ? null : lookup.accountId().apply(m.sentById()), m.createdAt());
            rows.add(new Row<>(row, BREF_CHAT_MESSAGE, m.id(), m.id(), Canonical.sha256(m.canonical()),
                    cut[0] ? List.of(Kind.WHATSAPP_FIELD_SHORTENED) : List.of()));
        }
        return rows;
    }

    // ── AI conversations ─────────────────────────────────────────────────────

    private static List<Row<ImportedConversation>> conversations(List<AiConversation> all, Lookup lookup,
                                                                 List<Kind> skipped) {
        Map<UUID, List<AiConversation>> byAccount = new LinkedHashMap<>();
        for (AiConversation a : sorted(all, AiConversation::createdAt, AiConversation::id)) {
            UUID bref = BrefMappings.uuidOrNull(a.userId());
            if (bref == null || !array(a.messages()) || !array(a.aiHistory())) {
                skipped.add(Kind.AI_CONVERSATION_UNREADABLE);
                continue;
            }
            UUID account = lookup.accountId().apply(bref);
            if (account == null) {
                skipped.add(Kind.AI_CONVERSATION_USER_UNKNOWN);
                continue;
            }
            byAccount.computeIfAbsent(account, k -> new ArrayList<>()).add(a);
        }
        List<Row<ImportedConversation>> rows = new ArrayList<>();
        byAccount.forEach((account, list) -> {
            // the most recent ones (21.4 cap per user)
            List<AiConversation> kept = list.stream()
                    .sorted(Comparator.comparing((AiConversation a) -> orCreated(a.updatedAt(), a.createdAt()))
                            .thenComparing(AiConversation::createdAt).thenComparing(a -> a.id().toString())
                            .reversed())
                    .limit(AiConversationImportPort.MAX_PER_USER).toList();
            for (int i = kept.size(); i < list.size(); i++) {
                skipped.add(Kind.AI_CONVERSATION_OVER_LIMIT);
            }
            Set<UUID> keep = new HashSet<>();
            kept.forEach(a -> keep.add(a.id()));
            for (AiConversation a : list) { // creation order
                if (!keep.contains(a.id())) {
                    continue;
                }
                // Bref caps the title at 120 too: the cut only guards a hand-edited row
                String title = a.title() == null ? "" : cut(a.title().strip(), 120, new boolean[1]);
                ImportedConversation row = new ImportedConversation(a.id(), account, title,
                        Math.min(10_000, Math.max(0, a.messageCount())), a.messages(), a.aiHistory(), a.createdAt(),
                        orCreated(a.updatedAt(), a.createdAt()));
                rows.add(new Row<>(row, BREF_AI_CONVERSATION, a.id(), a.id(), Canonical.sha256(a.canonical()),
                        List.of()));
            }
        });
        return rows;
    }

    // ── notifications ────────────────────────────────────────────────────────

    private static List<Row<ImportedNotification>> notifications(List<Notification> all, Lookup lookup,
                                                                 List<Kind> skipped) {
        List<Row<ImportedNotification>> rows = new ArrayList<>();
        for (Notification n : sorted(all, Notification::createdAt, Notification::id)) {
            UUID bref = BrefMappings.uuidOrNull(n.userId());
            String type = BrefMappings.trimmed(n.type());
            String title = BrefMappings.trimmed(n.title());
            if (bref == null || type == null || title == null || n.message() == null) {
                skipped.add(Kind.NOTIFICATION_UNREADABLE);
                continue;
            }
            UUID account = lookup.accountId().apply(bref);
            if (account == null) {
                skipped.add(Kind.NOTIFICATION_USER_UNKNOWN);
                continue;
            }
            boolean[] cut = {false};
            String data = n.metadata() != null && n.metadata().startsWith("{") ? n.metadata() : null;
            ImportedNotification row = new ImportedNotification(n.id(), account,
                    cut(type, NotificationImportPort.MAX_TYPE, cut), cut(title, NotificationImportPort.MAX_TITLE, cut),
                    cut(n.message(), NotificationImportPort.MAX_BODY, cut), data,
                    n.read() ? orCreated(n.updatedAt(), n.createdAt()) : null, n.createdAt());
            rows.add(new Row<>(row, BREF_NOTIFICATION, n.id(), n.id(), Canonical.sha256(n.canonical()),
                    cut[0] ? List.of(Kind.NOTIFICATION_SHORTENED) : List.of()));
        }
        return rows;
    }

    /** Browser subscriptions of the accounts Keevo has (keys kept as is: the browser holds the other half). */
    private static List<Row<ImportedWebPush>> webPush(List<WebPushSubscription> all, Lookup lookup,
                                                      List<Kind> skipped) {
        List<Row<ImportedWebPush>> rows = new ArrayList<>();
        for (WebPushSubscription w : sorted(all, WebPushSubscription::createdAt, WebPushSubscription::id)) {
            UUID bref = BrefMappings.uuidOrNull(w.userId());
            String endpoint = BrefMappings.trimmed(w.endpoint());
            String p256dh = BrefMappings.trimmed(w.p256dh());
            String auth = BrefMappings.trimmed(w.auth());
            if (bref == null || endpoint == null || length(endpoint) > 2048 || !endpoint.startsWith("https://")
                    || p256dh == null || length(p256dh) > 200 || auth == null || length(auth) > 100) {
                skipped.add(Kind.WEB_PUSH_UNREADABLE);
                continue;
            }
            UUID account = lookup.accountId().apply(bref);
            if (account == null) {
                skipped.add(Kind.WEB_PUSH_USER_UNKNOWN);
                continue;
            }
            ImportedWebPush row = new ImportedWebPush(w.id(), account, endpoint, p256dh, auth,
                    cut(BrefMappings.trimmed(w.userAgent()), 500, new boolean[1]), w.createdAt(),
                    orCreated(w.updatedAt(), w.createdAt()));
            rows.add(new Row<>(row, BREF_WEB_PUSH, w.id(), w.id(), Canonical.sha256(w.canonical()), List.of()));
        }
        return rows;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static <T> List<T> sorted(List<T> rows, Function<T, Instant> created, Function<T, UUID> id) {
        // PostgreSQL's uuid order = the text order: both sources and every run resolve duplicates alike
        return rows.stream().sorted(Comparator.comparing(created).thenComparing(r -> id.apply(r).toString()))
                .toList();
    }

    private static boolean array(String json) {
        return json != null && json.startsWith("[");
    }

    /** Digits of a phone when their count is within bounds, else {@code null}. */
    static String digits(String raw, int min, int max) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        return d.length() >= min && d.length() <= max ? d : null;
    }

    private static Integer intIn(Long v, long min, long max, boolean[] dropped) {
        Long kept = longIn(v, min, max, dropped);
        return kept == null ? null : kept.intValue();
    }

    private static Long longIn(Long v, long min, long max, boolean[] dropped) {
        if (v == null) {
            return null;
        }
        if (v < min || v > max) {
            dropped[0] = true;
            return null;
        }
        return v;
    }

    private static String upper(String v) {
        String t = BrefMappings.trimmed(v);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    private static int length(String v) {
        return v == null ? 0 : v.codePointCount(0, v.length());
    }

    private static Instant orCreated(Instant updated, Instant created) {
        return updated != null ? updated : created;
    }

    /** At most {@code max} code points (never half a character); sets {@code cut[0]} when shortened. */
    static String cut(String v, int max, boolean[] cut) {
        if (v == null || length(v) <= max) {
            return v;
        }
        cut[0] = true;
        return v.substring(0, v.offsetByCodePoints(0, max));
    }
}
