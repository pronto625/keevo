package com.keevo.whatsappcloud.inbox.adapter.in.rest;

import com.keevo.shared.capability.notification.PlatformWhatsApp.HeaderMedia;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.inbox.domain.model.ChatMessage;
import com.keevo.whatsappcloud.inbox.domain.model.Conversation;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Story 21.5 — the WhatsApp inbox of the company number (Bref {@code /v1/whatsapp/conversations}, same shapes): owner
 * only, module whatsapp-cloud (GET → READ, others → WRITE). {@code phone} = the contact's number, digits with the
 * country code (the {@code contactPhone} of the list).
 */
@RestController
@RequestMapping("/api/v1/whatsapp/conversations")
@PreAuthorize("hasRole('OWNER')")
public class WhatsAppConversationController {

    public record ReplyRequest(
            @NotBlank(message = "Le message est obligatoire")
            @Size(max = WhatsAppInboxService.MAX_TEXT, message = "Le message ne doit pas dépasser 4096 caractères")
            String text) {
    }

    /**
     * Story 21.6 — {@code headerMediaId} (from {@code POST /api/v1/whatsapp/media}) or {@code headerMediaLink}
     * (https) for an IMAGE / VIDEO / DOCUMENT template, nothing otherwise.
     */
    public record TemplateRequest(
            @NotNull(message = "Choisissez un modèle de message") UUID templateId,
            @Size(max = 20, message = "20 valeurs au plus") List<String> variables,
            String headerMediaId,
            String headerMediaLink) {
    }

    /** Bref's {@code ConversationSummary}. */
    public record ConversationView(String contactPhone, String contactName, String lastMessage, String lastType,
                                   String lastDirection, Instant lastAt, int unreadCount, Instant replyWindowEndsAt) {
        public static ConversationView of(Conversation c) {
            return new ConversationView(c.contactPhone(), c.contactName(), c.lastMessage(), c.lastType(),
                    c.lastDirection().name().toLowerCase(Locale.ROOT), c.lastAt(), c.unreadCount(),
                    WhatsAppInboxService.replyWindowEndsAt(c));
        }
    }

    /** Bref's {@code WhatsAppChatMessage}: direction {@code in} / {@code out}, status of a sent message lower case. */
    public record MessageView(UUID id, String contactPhone, String direction, String type, String body,
                              String status, Instant createdAt) {
        public static MessageView of(ChatMessage m) {
            return new MessageView(m.id(), m.contactPhone(), m.direction().name().toLowerCase(Locale.ROOT), m.type(),
                    m.body(), m.status() == null ? null : m.status().toLowerCase(Locale.ROOT), m.createdAt());
        }
    }

    public record UnreadView(long count) {
    }

    private final WhatsAppInboxService inbox;

    public WhatsAppConversationController(WhatsAppInboxService inbox) {
        this.inbox = inbox;
    }

    /** GET /api/v1/whatsapp/conversations — the 200 most recent conversations. */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<ConversationView>>> conversations() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(inbox.conversations(TenantContext.requireCurrent()).stream()
                .map(ConversationView::of).toList()));
    }

    /** GET /api/v1/whatsapp/conversations/unread-count — {@code {count}} (badge of the menu). */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponseWrapper<UnreadView>> unreadCount() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(new UnreadView(inbox.unreadCount(TenantContext.requireCurrent()))));
    }

    /**
     * GET /api/v1/whatsapp/conversations/{phone}/messages[?before=ISO-8601&amp;beforeId=uuid] — the 200 latest messages
     * (or the page before the oldest one shown: its {@code createdAt} and {@code id}), oldest → newest; the received
     * ones become read.
     */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{phone}/messages")
    public ResponseEntity<ApiResponseWrapper<List<MessageView>>> messages(
            @PathVariable String phone,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(view(inbox.thread(TenantContext.requireCurrent(), phone,
                before, beforeId))));
    }

    /**
     * POST /api/v1/whatsapp/conversations/{phone}/messages — {@code {text}}: free reply within 24 h of the client's
     * last message → the conversation; 422 {@code WHATSAPP_REPLY_WINDOW_CLOSED}, 409 {@code WHATSAPP_NOT_CONNECTED}.
     */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping("/{phone}/messages")
    public ResponseEntity<ApiResponseWrapper<List<MessageView>>> reply(@PathVariable String phone,
                                                                       @Valid @RequestBody ReplyRequest request) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(view(inbox.reply(TenantContext.requireCurrent(),
                CurrentActor.actorId(), phone, request.text()))));
    }

    /**
     * POST /api/v1/whatsapp/conversations/{phone}/template — {@code {templateId, variables}}: an approved template, at
     * any time (it reopens the conversation) → the conversation; 409 {@code WHATSAPP_TEMPLATE_NOT_APPROVED}. Story 21.6:
     * {@code headerMediaId} / {@code headerMediaLink} for a media template (400 when missing or not expected).
     */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping("/{phone}/template")
    public ResponseEntity<ApiResponseWrapper<List<MessageView>>> sendTemplate(
            @PathVariable String phone, @Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(view(inbox.sendTemplate(TenantContext.requireCurrent(),
                CurrentActor.actorId(), phone, request.templateId(), request.variables(),
                new HeaderMedia(request.headerMediaId(), request.headerMediaLink())))));
    }

    private static List<MessageView> view(List<ChatMessage> messages) {
        return messages.stream().map(MessageView::of).toList();
    }
}
