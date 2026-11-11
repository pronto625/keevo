package com.keevo.whatsappcloud.platform.adapter.in.rest;

import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.whatsappcloud.graph.domain.model.MetaTemplate;
import com.keevo.whatsappcloud.inbox.adapter.in.rest.MediaUploads;
import com.keevo.whatsappcloud.inbox.adapter.in.rest.WhatsAppConversationController.ConversationView;
import com.keevo.whatsappcloud.inbox.adapter.in.rest.WhatsAppConversationController.MessageView;
import com.keevo.whatsappcloud.inbox.adapter.in.rest.WhatsAppConversationController.ReplyRequest;
import com.keevo.whatsappcloud.inbox.adapter.in.rest.WhatsAppConversationController.UnreadView;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.inbox.domain.port.out.WhatsAppInboxRepository;
import com.keevo.whatsappcloud.platform.application.PlatformWhatsAppService;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.6 — the platform WhatsApp number for the super-admin (Bref {@code /v1/whatsapp/conversations} as superadmin,
 * {@code /v1/whatsapp/templates}): the conversations of clients and merchants who wrote to it, a reply inside the
 * 24-hour window, its templates (to build platform WhatsApp campaigns) and the header media upload. Same shapes as the
 * company inbox. ZERO business logic; {@link CurrentActor#requireSuperAdmin()} in every method (second barrier behind
 * the SecurityConfig {@code /api/v1/admin/**} rule).
 */
@Tag(name = "Admin — WhatsApp platform number", description = "Super Admin inbox and templates of the platform number")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/whatsapp")
public class AdminWhatsAppController {

    /** A platform template: its review status in French, the number of body variables to fill in a campaign. */
    public record PlatformTemplateView(String name, String language, String status, String statusLabel,
                                       String category, String headerFormat, String bodyText, int variableCount) {
        static PlatformTemplateView of(MetaTemplate t) {
            String status = t.status() == null ? "PENDING" : t.status().toUpperCase(java.util.Locale.ROOT);
            return new PlatformTemplateView(t.name(), t.language(), status, label(status), t.category(),
                    t.headerFormat() == null ? "NONE" : t.headerFormat(), t.bodyText(),
                    TemplateText.variables(t.bodyText()).size());
        }

        static String label(String status) {
            return switch (status) {
                case "APPROVED" -> "Approuvé";
                case "REJECTED" -> "Refusé";
                case "PAUSED" -> "En pause";
                case "DISABLED" -> "Désactivé";
                default -> "En cours de validation";
            };
        }
    }

    public record MediaView(String mediaId) {
    }

    private final WhatsAppInboxService inbox;
    private final PlatformWhatsAppService platform;

    public AdminWhatsAppController(WhatsAppInboxService inbox, PlatformWhatsAppService platform) {
        this.inbox = inbox;
        this.platform = platform;
    }

    @Operation(summary = "Conversations of the platform number, 200 most recent",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/conversations")
    public ResponseEntity<ApiResponseWrapper<List<ConversationView>>> conversations() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(inbox.conversations(WhatsAppInboxRepository.PLATFORM).stream()
                .map(ConversationView::of).toList()));
    }

    @Operation(summary = "Unread messages of the platform number", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/conversations/unread-count")
    public ResponseEntity<ApiResponseWrapper<UnreadView>> unreadCount() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(new UnreadView(inbox.unreadCount(
                WhatsAppInboxRepository.PLATFORM))));
    }

    @Operation(summary = "Messages of one conversation, oldest to newest (page before = createdAt / id of the oldest)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/conversations/{phone}/messages")
    public ResponseEntity<ApiResponseWrapper<List<MessageView>>> messages(
            @PathVariable String phone,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(inbox.thread(WhatsAppInboxRepository.PLATFORM, phone, before,
                beforeId).stream().map(MessageView::of).toList()));
    }

    @Operation(summary = "Free reply from the platform number within 24 h of the contact's last message; 422 "
            + "WHATSAPP_REPLY_WINDOW_CLOSED, 503 PLATFORM_WHATSAPP_UNAVAILABLE",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/conversations/{phone}/messages")
    public ResponseEntity<ApiResponseWrapper<List<MessageView>>> reply(@PathVariable String phone,
                                                                       @Valid @RequestBody ReplyRequest request) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(inbox.reply(WhatsAppInboxRepository.PLATFORM,
                CurrentActor.actorId(), phone, request.text()).stream().map(MessageView::of).toList()));
    }

    @Operation(summary = "Templates of the platform number as Meta lists them (approved first); 503 when the number "
            + "or its WABA is not configured", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/templates")
    public ResponseEntity<ApiResponseWrapper<List<PlatformTemplateView>>> templates() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(platform.templates().stream().map(PlatformTemplateView::of)
                .toList()));
    }

    @Operation(summary = "Uploads the media of a template header to the platform number (JPEG / PNG ≤ 5 MB, MP4 / PDF "
            + "≤ 10 MB) → mediaId for a platform campaign", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<MediaView>> media(@RequestParam("file") MultipartFile file)
            throws IOException {
        CurrentActor.requireSuperAdmin();
        MediaUploads.Media media = MediaUploads.check(file);
        return ResponseEntity.ok(ApiResponseWrapper.ok(new MediaView(inbox.uploadMedia(
                WhatsAppInboxRepository.PLATFORM, media.content(), media.type(), media.filename()))));
    }
}
