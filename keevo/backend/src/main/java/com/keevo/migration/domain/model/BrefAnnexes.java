package com.keevo.migration.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.6 — the annex section of one Bref owner, as read from the source, unchanged (field names of the api-bref
 * entities, {@code origin/develop} 6faa8b4): the WhatsApp Business account, the owner's message templates, his customer
 * campaigns and their messages (the recipients), his WhatsApp inbox, the AI conversations and in-app notifications of
 * the owner and the members. Both section sources (restored database, JSON export) give the same rows; a table an older
 * Bref schema lacks reads as empty. JSON columns are kept as canonical JSON text (object keys sorted), so both sources
 * give the same text and checksums.
 *
 * <p>Platform-level rows are not part of it: platform campaigns ({@code campaign}) and their messages, the platform
 * number's chat ({@code ownerId} NULL), the app's push tokens (the new app registers its own). Browser (Web Push)
 * subscriptions are imported: Keevo signs with Bref's VAPID keys (19.9b), so they stay valid.
 */
public record BrefAnnexes(WhatsAppAccount whatsappAccount, List<Template> templates, List<Campaign> campaigns,
                          List<CampaignMessage> campaignMessages, List<ChatMessage> chatMessages,
                          List<AiConversation> aiConversations, List<Notification> notifications,
                          List<WebPushSubscription> webPushSubscriptions) {

    /** Bref source tables (record {@code source_table}). */
    public static final String BREF_WHATSAPP_ACCOUNT = "whatsapp_account";
    public static final String BREF_TEMPLATE = "owner_whatsapp_template";
    public static final String BREF_CAMPAIGN = "customer_campaign";
    public static final String BREF_CAMPAIGN_MESSAGE = "whatsapp_message";
    public static final String BREF_CHAT_MESSAGE = "whatsapp_chat_message";
    public static final String BREF_AI_CONVERSATION = "ai_conversation";
    public static final String BREF_NOTIFICATION = "notification";
    public static final String BREF_WEB_PUSH = "web_push_subscription";

    public BrefAnnexes {
        templates = templates == null ? List.of() : List.copyOf(templates);
        campaigns = campaigns == null ? List.of() : List.copyOf(campaigns);
        campaignMessages = campaignMessages == null ? List.of() : List.copyOf(campaignMessages);
        chatMessages = chatMessages == null ? List.of() : List.copyOf(chatMessages);
        aiConversations = aiConversations == null ? List.of() : List.copyOf(aiConversations);
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
        webPushSubscriptions = webPushSubscriptions == null ? List.of() : List.copyOf(webPushSubscriptions);
    }

    public static BrefAnnexes empty() {
        return new BrefAnnexes(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * A Bref {@code whatsapp_account} row ({@code status} connected | disconnected). The token is Meta's, in clear as
     * Bref stores it: never in {@link #toString()} nor in {@link #canonical()}.
     */
    public record WhatsAppAccount(UUID id, UUID ownerId, String wabaId, String phoneNumberId,
                                  String displayPhoneNumber, String verifiedName, String accessToken, String status,
                                  Instant createdAt, Instant updatedAt) {

        public WhatsAppAccount {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public boolean connected() {
            return status != null && "connected".equalsIgnoreCase(status.strip());
        }

        /** Canonical form without the token (a checksum of a secret is never kept). */
        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "wabaId", wabaId, "phoneNumberId", phoneNumberId,
                    "displayPhoneNumber", displayPhoneNumber, "verifiedName", verifiedName, "status", status,
                    "createdAt", createdAt, "updatedAt", updatedAt);
        }

        @Override
        public String toString() {
            return "WhatsAppAccount[id=" + id + ", status=" + status + ", token=***]";
        }
    }

    /** A Bref {@code owner_whatsapp_template} row. */
    public record Template(UUID id, UUID ownerId, String name, String category, String language,
                           String headerFormat, String headerText, String headerMediaUrl, String bodyText,
                           List<String> bodyExamples, String footerText, String status, String metaTemplateId,
                           String rejectedReason, String submitError, Instant createdAt, Instant updatedAt) {

        public Template {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
            bodyExamples = bodyExamples == null ? List.of() : List.copyOf(bodyExamples);
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "name", name, "category", category, "language",
                    language, "headerFormat", headerFormat, "headerText", headerText, "headerMediaUrl", headerMediaUrl,
                    "bodyText", bodyText, "bodyExamples", String.join("\u001f", bodyExamples), "footerText",
                    footerText, "status", status, "metaTemplateId", metaTemplateId, "rejectedReason", rejectedReason,
                    "submitError", submitError, "createdAt", createdAt, "updatedAt", updatedAt);
        }
    }

    /**
     * A Bref {@code customer_campaign} row ({@code status} draft | scheduled | sending | sent | cancelled).
     *
     * @param targetingCriteria canonical JSON object text ({@code shopIds}, purchase criteria), or {@code null}
     * @param whatsapp          canonical JSON object text ({@code templateName, language, headerFormat,
     *                          headerMediaUrl, bodyVariables, bodyText}), or {@code null}
     */
    public record Campaign(UUID id, UUID ownerId, String name, String description, String status,
                           String targetingCriteria, String whatsapp, Targeting targeting, Content content,
                           int targetedCount, int sentCount, int deliveredCount, int readCount, int failedCount,
                           Instant scheduledAt, Instant sentAt, UUID createdById, Instant createdAt,
                           Instant updatedAt) {

        public Campaign {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
            targeting = targeting == null ? Targeting.NONE : targeting;
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "name", name, "description", description, "status",
                    status, "targetingCriteria", targetingCriteria, "whatsapp", whatsapp, "targetedCount",
                    targetedCount, "sentCount", sentCount, "deliveredCount", deliveredCount, "readCount", readCount,
                    "failedCount", failedCount, "scheduledAt", scheduledAt, "sentAt", sentAt, "createdById",
                    createdById, "createdAt", createdAt, "updatedAt", updatedAt);
        }
    }

    /**
     * The campaign's {@code targetingCriteria} as read ({@code null} = absent). A value Bref holds that is not a whole
     * number (or a shop id that is not a UUID) is left out and {@code unreadable} is set.
     */
    public record Targeting(List<UUID> shopIds, Long inactiveForDays, Long purchasedWithinDays, Long totalSpentMin,
                            Long totalSpentMax, Long totalInvoicesMin, Long totalInvoicesMax, boolean unreadable) {

        public static final Targeting NONE = new Targeting(List.of(), null, null, null, null, null, null, false);

        public Targeting {
            shopIds = shopIds == null ? List.of() : List.copyOf(shopIds);
        }
    }

    /** The campaign's {@code whatsapp} settings as read ({@code bodyVariables} in order). */
    public record Content(String templateName, String language, String headerFormat, String headerMediaUrl,
                          String bodyText, List<Variable> variables) {

        public Content {
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }

    /** One body variable ({@code source} static | customerName | shopName). */
    public record Variable(String paramName, String source, String value) {
    }

    /** A Bref {@code whatsapp_message} row of a customer campaign ({@code status} pending|sent|delivered|read|failed). */
    public record CampaignMessage(UUID id, UUID customerCampaignId, UUID customerId, String phone, String templateName,
                                  String waMessageId, String status, String error, Instant createdAt,
                                  Instant updatedAt) {

        public CampaignMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public String canonical() {
            return Canonical.of("id", id, "customerCampaignId", customerCampaignId, "customerId", customerId,
                    "phone", phone, "templateName", templateName, "waMessageId", waMessageId, "status", status,
                    "error", error, "createdAt", createdAt, "updatedAt", updatedAt);
        }
    }

    /** A Bref {@code whatsapp_chat_message} row of the owner's number ({@code direction} in | out). */
    public record ChatMessage(UUID id, UUID ownerId, String contactPhone, String contactName, String direction,
                              String type, String body, String waMessageId, String status, Instant readAt,
                              UUID sentById, Instant createdAt) {

        public ChatMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "contactPhone", contactPhone, "contactName",
                    contactName, "direction", direction, "type", type, "body", body, "waMessageId", waMessageId,
                    "status", status, "readAt", readAt, "sentById", sentById, "createdAt", createdAt);
        }
    }

    /**
     * A Bref {@code ai_conversation} row ({@code userId} text: any member).
     *
     * @param messages  canonical JSON text (array expected)
     * @param aiHistory canonical JSON text (array expected)
     */
    public record AiConversation(UUID id, String userId, String title, int messageCount, String messages,
                                 String aiHistory, Instant createdAt, Instant updatedAt) {

        public AiConversation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public String canonical() {
            return Canonical.of("id", id, "userId", userId, "title", title, "messageCount", messageCount,
                    "messages", messages, "aiHistory", aiHistory, "createdAt", createdAt, "updatedAt", updatedAt);
        }
    }

    /**
     * A Bref {@code notification} row ({@code type} invoice | payment | reminder | alert | system | campaign;
     * {@code channel} push | in_app).
     *
     * @param metadata canonical JSON text, or {@code null}
     */
    public record Notification(UUID id, String userId, String type, String channel, String title, String message,
                               boolean read, String metadata, Instant createdAt, Instant updatedAt) {

        public Notification {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public String canonical() {
            return Canonical.of("id", id, "userId", userId, "type", type, "channel", channel, "title", title,
                    "message", message, "isRead", read, "metadata", metadata, "createdAt", createdAt, "updatedAt",
                    updatedAt);
        }
    }

    /**
     * A Bref {@code web_push_subscription} row (a browser of any member). The keys are not in {@link #canonical()} nor
     * in {@link #toString()}.
     */
    public record WebPushSubscription(UUID id, String userId, String endpoint, String p256dh, String auth,
                                      String userAgent, Instant createdAt, Instant updatedAt) {

        public WebPushSubscription {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public String canonical() {
            return Canonical.of("id", id, "userId", userId, "endpoint", endpoint, "userAgent", userAgent,
                    "createdAt", createdAt, "updatedAt", updatedAt);
        }

        @Override
        public String toString() {
            return "WebPushSubscription[id=" + id + ", keys=***]";
        }
    }
}
