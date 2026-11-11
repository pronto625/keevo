package com.keevo.admin.campaign.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.keevo.admin.campaign.application.PlatformCampaignService;
import com.keevo.admin.campaign.application.PlatformCampaignService.Detail;
import com.keevo.admin.campaign.application.PlatformCampaignService.Draft;
import com.keevo.admin.campaign.application.PlatformCampaignService.Presence;
import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.ChannelStats;
import com.keevo.admin.campaign.domain.model.HistoryEntry;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.model.Segment;
import com.keevo.admin.campaign.domain.model.WhatsAppContent;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.6 — the super-admin's platform campaigns (Bref {@code /v1/campaigns}, 20.13 route K1): campaigns to the
 * owners of a segment of companies over push, WhatsApp (platform number), SMS and email, sent in the background, with
 * counters per channel and an audited history. ZERO business logic; {@link CurrentActor#requireSuperAdmin()} in every
 * method (second barrier behind the SecurityConfig {@code /api/v1/admin/**} rule). French labels everywhere.
 */
@Tag(name = "Admin — Campaigns", description = "Super Admin platform campaigns")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/campaigns")
public class AdminCampaignController {

    public record ChannelStatsView(String channel, String channelLabel, int targeted, int sent, int delivered,
                                   int read, int failed, int skipped, int uncertain) {
        static ChannelStatsView of(ChannelStats s) {
            return new ChannelStatsView(s.channel().name(), s.channel().label(), s.targeted(), s.sent(),
                    s.delivered(), s.read(), s.failed(), s.skipped(), s.uncertain());
        }
    }

    public record HistoryView(String action, String actionLabel, String actorName, String detail, Instant at) {
        static HistoryView of(HistoryEntry h) {
            return new HistoryView(h.action(), h.actionLabel(), h.actorName(), h.detail(), h.occurredAt());
        }
    }

    public record CampaignView(UUID id, String name, String description, String status, String statusLabel,
                               List<String> channels, List<String> channelLabels, String title, String message,
                               Segment segment, WhatsAppContent whatsapp, Instant scheduledAt, Instant startedAt,
                               Instant sentAt, int targetedCount, List<ChannelStatsView> stats,
                               List<HistoryView> history, Instant createdAt, Instant updatedAt) {
        static CampaignView of(Detail d) {
            PlatformCampaign c = d.campaign();
            return new CampaignView(c.id(), c.name(), c.description(), c.status().name(), c.status().label(),
                    c.channels().stream().map(Enum::name).toList(), c.channels().stream().map(Channel::label).toList(),
                    c.title(), c.message(), c.segment(), c.whatsapp(), c.scheduledAt(), c.startedAt(), c.sentAt(),
                    c.targetedCount(), d.stats().stream().map(ChannelStatsView::of).toList(),
                    d.history().stream().map(HistoryView::of).toList(), c.createdAt(), c.updatedAt());
        }
    }

    public record PreviewView(int owners, Map<Channel, Integer> byChannel, boolean overLimit) {
    }

    private final PlatformCampaignService campaigns;

    public AdminCampaignController(PlatformCampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @Operation(summary = "Platform campaigns, 50 newest first (next page: before = createdAt, beforeId = id of the "
            + "last)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<CampaignView>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(campaigns.list(before, beforeId).stream().map(CampaignView::of)
                .toList()));
    }

    @Operation(summary = "Owners the segment reaches now, per channel", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/preview-targeting")
    public ResponseEntity<ApiResponseWrapper<PreviewView>> preview(@RequestBody(required = false) JsonNode body) {
        CurrentActor.requireSuperAdmin();
        JsonNode segment = body == null ? NullNode.getInstance() : body.has("segment") ? body.get("segment") : body;
        PlatformCampaignService.Preview p = campaigns.preview(segment(segment));
        return ResponseEntity.ok(ApiResponseWrapper.ok(new PreviewView(p.owners(), p.byChannel(), p.overLimit())));
    }

    @Operation(summary = "One campaign with its counters per channel and its history",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> get(@PathVariable UUID id) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(CampaignView.of(campaigns.get(id))));
    }

    @Operation(summary = "Creates a campaign (DRAFT, or SCHEDULED with scheduledAt)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<CampaignView>> create(@RequestBody(required = false) JsonNode body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(CampaignView.of(
                campaigns.create(CurrentActor.actorId(), draft(body)))));
    }

    @Operation(summary = "Changes a DRAFT / SCHEDULED campaign (fields present only; scheduledAt null unschedules)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> update(@PathVariable UUID id,
                                                                   @RequestBody(required = false) JsonNode body) {
        CurrentActor.requireSuperAdmin();
        JsonNode b = body == null ? NullNode.getInstance() : body;
        Presence p = new Presence(b.has("name"), b.has("description"), b.has("channels"), b.has("title"),
                b.has("message"), b.has("segment"), b.has("whatsapp"), b.has("scheduledAt"));
        return ResponseEntity.ok(ApiResponseWrapper.ok(CampaignView.of(campaigns.update(CurrentActor.actorId(), id,
                draft(body), p))));
    }

    @Operation(summary = "Deletes a campaign not sent (DRAFT, SCHEDULED, CANCELLED); 409 otherwise",
            security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentActor.requireSuperAdmin();
        campaigns.delete(CurrentActor.actorId(), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Sends now: the audience is frozen, messages leave in the background", security =
            @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{id}/send")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> send(@PathVariable UUID id) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(CampaignView.of(campaigns.send(CurrentActor.actorId(), id))));
    }

    @Operation(summary = "Cancels a SCHEDULED campaign", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> cancel(@PathVariable UUID id) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(CampaignView.of(campaigns.cancel(CurrentActor.actorId(), id))));
    }

    // ── body parsing (presence-aware, French errors) ──────────────────────────────────────────────────────────────

    static Draft draft(JsonNode body) {
        if (body == null || body.isNull()) {
            return new Draft(null, null, null, null, null, null, null, null);
        }
        if (!body.isObject()) {
            throw bad("body", "Requête invalide");
        }
        return new Draft(text(body, "name"), text(body, "description"), strings(body.get("channels"), "channels"),
                text(body, "title"), text(body, "message"),
                body.has("segment") ? segment(body.get("segment")) : null,
                whatsapp(body.get("whatsapp")), instant(body.get("scheduledAt")));
    }

    static Segment segment(JsonNode n) {
        if (n == null || n.isNull()) {
            return Segment.ALL;
        }
        if (!n.isObject()) {
            throw bad("segment", "Segment invalide");
        }
        List<UUID> tenants = new ArrayList<>();
        List<String> raw = strings(n.get("tenantIds"), "segment.tenantIds");
        if (raw != null) {
            for (String r : raw) {
                try {
                    tenants.add(UUID.fromString(r));
                } catch (IllegalArgumentException e) {
                    throw bad("segment.tenantIds", "Entreprise inconnue dans le segment");
                }
            }
        }
        return new Segment(strings(n.get("planCodes"), "segment.planCodes"), strings(n.get("countries"),
                "segment.countries"), strings(n.get("activities"), "segment.activities"), tenants);
    }

    static WhatsAppContent whatsapp(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            throw bad("whatsapp", "Réglages WhatsApp invalides");
        }
        List<WhatsAppContent.Variable> variables = new ArrayList<>();
        JsonNode vars = n.has("variables") ? n.get("variables") : n.get("bodyVariables");
        if (vars != null && !vars.isNull()) {
            if (!vars.isArray() || vars.size() > 20) {
                throw bad("whatsapp.variables", "20 variables au plus");
            }
            for (JsonNode v : vars) {
                variables.add(new WhatsAppContent.Variable(v.path("source").isTextual() ? v.get("source").asText()
                        : null, v.path("value").isTextual() ? v.get("value").asText() : null));
            }
        }
        return new WhatsAppContent(text(n, "templateName"), text(n, "language"), text(n, "bodyText"),
                text(n, "headerFormat"), text(n, "headerMediaId"), text(n, "headerMediaLink"), variables);
    }

    private static List<String> strings(JsonNode n, String field) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray() || n.size() > 100) {
            throw bad(field, "Liste invalide (100 valeurs au plus)");
        }
        List<String> out = new ArrayList<>();
        n.forEach(v -> {
            if (!v.isTextual()) {
                throw bad(field, "Liste invalide");
            }
            out.add(v.asText());
        });
        return out;
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw bad(field, "Valeur invalide");
        }
        return n.asText();
    }

    private static Instant instant(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return Instant.parse(n.asText());
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(n.asText()).toInstant();
            } catch (DateTimeParseException e2) {
                throw bad("scheduledAt", "Date d'envoi invalide (format ISO 8601 attendu)");
            }
        }
    }

    private static InvalidRequestParameterException bad(String field, String message) {
        return new InvalidRequestParameterException(field, "invalid", message);
    }
}
