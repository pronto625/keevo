package com.keevo.whatsappcloud.inbox.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService.Credentials;
import com.keevo.shared.capability.notification.PlatformWhatsApp.HeaderMedia;
import com.keevo.whatsappcloud.config.MetaErrors;
import com.keevo.whatsappcloud.config.PlatformNumberSettings;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import com.keevo.whatsappcloud.inbox.domain.model.ChatMessage;
import com.keevo.whatsappcloud.inbox.domain.model.ContactPhone;
import com.keevo.whatsappcloud.inbox.domain.model.Conversation;
import com.keevo.whatsappcloud.inbox.domain.model.ConversationDelta;
import com.keevo.whatsappcloud.inbox.domain.port.out.WhatsAppInboxRepository;
import com.keevo.whatsappcloud.template.application.WhatsAppTemplateService;
import com.keevo.whatsappcloud.template.domain.model.MessageTemplate;
import com.keevo.whatsappcloud.template.domain.model.TemplateComponents;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.5 — the company's WhatsApp inbox (port of Bref {@code WhatsAppInboxService}): the conversations, one
 * conversation's messages (opening it marks the received ones read), the unread badge, a free-text reply — allowed by
 * Meta only within 24 h of the contact's last message ({@code WHATSAPP_REPLY_WINDOW_CLOSED} otherwise, nothing sent) —
 * and an approved template, which reopens a conversation at any time. Every message leaves from the company's own
 * number and is stored as sent ({@code OUT}).
 *
 * <p>Costs: list = 1 index scan (LIMIT 200); thread = 1 index scan (LIMIT 200) + 1 UPDATE on the partial unread index;
 * badge = 1 partial-index sum; reply = 1 PK read + 1 Graph call + 1 insert + 1 upsert (one short transaction).
 */
@Service
public class WhatsAppInboxService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInboxService.class);

    public static final int PAGE = 200;
    public static final Duration REPLY_WINDOW = Duration.ofHours(24);
    public static final int MAX_TEXT = 4096;
    static final int MAX_VARIABLE = 1024;

    private final WhatsAppInboxRepository inbox;
    private final WhatsAppAccountService accounts;
    private final WhatsAppTemplateService templates;
    private final MetaGraphClient graph;
    private final PlatformNumberSettings platform;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WhatsAppInboxService(WhatsAppInboxRepository inbox, WhatsAppAccountService accounts,
                                WhatsAppTemplateService templates, MetaGraphClient graph,
                                PlatformNumberSettings platform, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.inbox = inbox;
        this.accounts = accounts;
        this.templates = templates;
        this.graph = graph;
        this.platform = platform;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public List<Conversation> conversations(String schema) {
        return inbox.conversations(schema, PAGE);
    }

    public long unreadCount(String schema) {
        return inbox.unreadCount(schema);
    }

    /**
     * One conversation, oldest → newest (the {@value #PAGE} latest, or the page before the keyset {@code before} +
     * {@code beforeId} = the oldest message already shown); opening the latest page marks the received messages
     * read.
     */
    public List<ChatMessage> thread(String schema, String rawPhone, Instant before, UUID beforeId) {
        String phone = ContactPhone.require(rawPhone);
        List<ChatMessage> newestFirst = inbox.thread(schema, phone, before, before == null ? null : beforeId, PAGE);
        if (before == null) {
            inbox.markRead(schema, phone, clock.instant());
        }
        List<ChatMessage> ordered = new ArrayList<>(newestFirst);
        Collections.reverse(ordered);
        return ordered;
    }

    /** End of the free-reply window of a conversation ({@code null} when the contact never wrote). */
    public static Instant replyWindowEndsAt(Conversation c) {
        return c.lastInboundAt() == null ? null : c.lastInboundAt().plus(REPLY_WINDOW);
    }

    /**
     * Free-text reply from the company's number, inside the 24-hour window.
     *
     * @return the conversation's latest messages, oldest → newest
     * @throws DomainException {@code WHATSAPP_REPLY_WINDOW_CLOSED} 422, {@code WHATSAPP_NOT_CONNECTED} 409,
     *                         {@code WHATSAPP_CLOUD_META_ERROR} 502, {@code WHATSAPP_SEND_UNCERTAIN} 409
     */
    public List<ChatMessage> reply(String schema, UUID actorId, String rawPhone, String rawText) {
        String phone = ContactPhone.require(rawPhone);
        String text = rawText == null ? "" : rawText.strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw new InvalidRequestParameterException("text", "length",
                    "Le message est obligatoire (4096 caractères au plus)");
        }
        Instant now = clock.instant();
        Conversation c = inbox.conversation(schema, phone).orElse(null);
        Instant endsAt = c == null ? null : replyWindowEndsAt(c);
        if (endsAt == null || !now.isBefore(endsAt)) {
            throw new DomainException(ErrorCode.WHATSAPP_REPLY_WINDOW_CLOSED);
        }
        Credentials creds = credentials(schema);
        String waId;
        try {
            waId = graph.sendText(creds.phoneNumberId(), creds.token(), phone, text);
        } catch (MetaGraphException e) {
            log.warn("[WhatsApp Cloud] reply not sent for tenant {} ({})", schema, e.kind());
            throw MetaErrors.of(e, true);
        }
        recordOutbound(schema, phone, "text", text, waId, actorId);
        return thread(schema, phone, null, null);
    }

    /**
     * Sends an approved template of the owner (with its body variables and, Story 21.6, the media of a media header:
     * an id from {@code POST /whatsapp/media} or an https link), at any time.
     *
     * @throws DomainException {@code WHATSAPP_TEMPLATE_NOT_FOUND} 404, {@code WHATSAPP_TEMPLATE_NOT_APPROVED} 409,
     *                         {@code WHATSAPP_NOT_CONNECTED} 409, {@code WHATSAPP_CLOUD_META_ERROR} 502,
     *                         {@code WHATSAPP_SEND_UNCERTAIN} 409
     */
    public List<ChatMessage> sendTemplate(String schema, UUID actorId, String rawPhone, UUID templateId,
                                          List<String> variables, HeaderMedia header) {
        String phone = ContactPhone.require(rawPhone);
        MessageTemplate t = templates.approved(schema, templateId);
        String problem = TemplateComponents.headerProblem(t.headerFormat(), header);
        if (problem != null) {
            throw new InvalidRequestParameterException("headerMedia", "media", problem);
        }
        List<String> keys = TemplateText.variables(t.bodyText());
        List<String> values = (variables == null ? List.<String>of() : variables).stream()
                .map(v -> v == null ? "" : v.strip()).toList();
        if (values.size() != keys.size() || values.stream().anyMatch(v -> v.isEmpty() || v.length() > MAX_VARIABLE)) {
            throw new InvalidRequestParameterException("variables", "count",
                    "Renseignez " + keys.size() + (keys.size() > 1 ? " valeurs" : " valeur") + " pour ce modèle");
        }
        Credentials creds = credentials(schema);
        List<Map<String, Object>> components = TemplateComponents.build(t.bodyText(), values, t.headerFormat(),
                header);
        String waId;
        try {
            waId = graph.sendTemplate(creds.phoneNumberId(), creds.token(), phone, t.name(), t.language(), components);
        } catch (MetaGraphException e) {
            log.warn("[WhatsApp Cloud] template not sent for tenant {} ({})", schema, e.kind());
            throw MetaErrors.of(e, true);
        }
        recordOutbound(schema, phone, "template", TemplateText.render(t.bodyText(), values), waId, actorId);
        return thread(schema, phone, null, null);
    }

    /** Same, without a header media (21.5 shape). */
    public List<ChatMessage> sendTemplate(String schema, UUID actorId, String rawPhone, UUID templateId,
                                          List<String> variables) {
        return sendTemplate(schema, actorId, rawPhone, templateId, variables, null);
    }

    /**
     * Story 21.6 — uploads a media file to the sending number (company or platform) for a media header → Meta's
     * media id (valid 30 days at Meta).
     *
     * @throws DomainException {@code WHATSAPP_NOT_CONNECTED} 409, {@code WHATSAPP_CLOUD_META_ERROR} 502
     */
    public String uploadMedia(String schema, byte[] content, String mimeType, String filename) {
        Credentials creds = credentials(schema);
        try {
            return graph.uploadMedia(creds.phoneNumberId(), creds.token(), content, mimeType, filename);
        } catch (MetaGraphException e) {
            log.warn("[WhatsApp Cloud] media not uploaded for {} ({})", schema, e.kind());
            throw MetaErrors.of(e, false);
        }
    }

    /**
     * Stores a message sent from the company number (reply, template, invoice) in its conversation: one insert + one
     * upsert in one short transaction. Never fails the send: a storage failure is logged.
     */
    public void recordOutbound(String schema, String phone, String type, String body, String waId, UUID actorId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String stored = body == null ? null : body.length() > 8192 ? body.substring(0, 8192) : body;
        try {
            tx.executeWithoutResult(s -> {
                inbox.insertAll(schema, List.of(new ChatMessage(UUID.randomUUID(), phone, ChatMessage.Direction.OUT,
                        type, stored, waId, "SENT", null, actorId, now)));
                inbox.upsertConversations(schema, List.of(new ConversationDelta(phone, null, preview(stored), type,
                        ChatMessage.Direction.OUT, now, 0, null)));
            });
        } catch (RuntimeException e) {
            log.warn("[WhatsApp Cloud] sent message not stored for tenant {} ({})", schema,
                    e.getClass().getSimpleName());
        }
    }

    /** The conversation preview: the first 500 characters. */
    public static String preview(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > 500 ? body.substring(0, 499) + "…" : body;
    }

    /**
     * The sending number of an inbox: the company's connected WABA, or (Story 21.6) the platform number for
     * {@link WhatsAppInboxRepository#PLATFORM}.
     */
    public Credentials credentials(String schema) {
        if (WhatsAppInboxRepository.PLATFORM.equals(schema)) {
            if (!platform.isConfigured() || !graph.isEnabled()) {
                throw new DomainException(ErrorCode.PLATFORM_WHATSAPP_UNAVAILABLE);
            }
            return new Credentials(platform.wabaId(), platform.phoneNumberId(), platform.token());
        }
        return accounts.credentials(schema).orElseThrow(() -> new DomainException(ErrorCode.WHATSAPP_NOT_CONNECTED));
    }
}
