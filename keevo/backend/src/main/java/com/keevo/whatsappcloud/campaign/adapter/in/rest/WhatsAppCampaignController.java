package com.keevo.whatsappcloud.campaign.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.whatsappcloud.campaign.application.CampaignService;
import com.keevo.whatsappcloud.campaign.application.CampaignService.Draft;
import com.keevo.whatsappcloud.campaign.application.CampaignService.Presence;
import com.keevo.whatsappcloud.campaign.application.CampaignService.VariableInput;
import com.keevo.whatsappcloud.campaign.domain.model.Campaign;
import com.keevo.whatsappcloud.campaign.domain.model.PurchaseCriteria;
import com.keevo.whatsappcloud.campaign.domain.model.Recipient;
import com.keevo.whatsappcloud.campaign.domain.model.RecipientStatus;
import com.keevo.whatsappcloud.campaign.domain.model.VariableMapping;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignRepository.CampaignRow;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
import com.keevo.whatsappcloud.template.domain.model.MessageTemplate;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.6 — the company's WhatsApp campaigns to its consenting clients (Bref {@code /v1/customer-campaigns}): owner
 * only, module whatsapp-cloud (GET → READ, others → WRITE). Statuses carry a French {@code statusLabel}; stores are
 * shown by name ({@code storeNames}), their ids are only for the edit form.
 */
@RestController
@RequestMapping("/api/v1/whatsapp/campaigns")
@PreAuthorize("hasRole('OWNER')")
public class WhatsAppCampaignController {

    /** A variable as shown and edited: source {@code static | clientName | storeName}, fixed text or fallback. */
    public record VariableView(String source, String value) {
        static VariableView of(VariableMapping v) {
            String source = switch (v.source()) {
                case STATIC -> "static";
                case CLIENT_NAME -> "clientName";
                case STORE_NAME -> "storeName";
            };
            return new VariableView(source, v.value());
        }
    }

    public record CampaignView(UUID id, String name, String description, String status, String statusLabel,
                               List<UUID> storeIds, List<String> storeNames, UUID templateId, String templateName,
                               String templateLanguage, String templateBody, String headerFormat,
                               boolean hasHeaderMedia, String headerMediaLink, List<VariableView> variables,
                               Instant scheduledAt, Instant startedAt, Instant sentAt, int targetedCount,
                               int sentCount, int deliveredCount, int readCount, int failedCount, int skippedCount,
                               int uncertainCount, Instant createdAt, Instant updatedAt, Integer inactiveForDays,
                               Integer purchasedWithinDays, Long totalSpentMin, Long totalSpentMax,
                               Integer totalInvoicesMin, Integer totalInvoicesMax,
                               List<String> purchaseCriteriaLabels) {
        static CampaignView of(CampaignRow row, Map<UUID, String> names) {
            Campaign c = row.campaign();
            Campaign.Counters n = c.counters();
            return new CampaignView(c.id(), c.name(), c.description(), c.status().name(), c.status().label(),
                    c.storeIds(), c.storeIds().stream().map(id -> names.getOrDefault(id, "Boutique supprimée"))
                    .toList(), c.templateId(), row.templateName(), row.templateLanguage(), row.templateBody(),
                    row.templateHeader() == null ? null : row.templateHeader(),
                    c.headerMediaId() != null || c.headerMediaLink() != null, c.headerMediaLink(),
                    c.variables().stream().map(VariableView::of).toList(), c.scheduledAt(), c.startedAt(), c.sentAt(),
                    n.targeted(), n.sent(), n.delivered(), n.read(), n.failed(), n.skipped(), n.uncertain(),
                    c.createdAt(), c.updatedAt(), c.criteria().inactiveForDays(), c.criteria().purchasedWithinDays(),
                    c.criteria().totalSpentMin(), c.criteria().totalSpentMax(), c.criteria().totalInvoicesMin(),
                    c.criteria().totalInvoicesMax(), c.criteria().labels());
        }
    }

    /** An approved template a campaign can use, with its number of variables. */
    public record TemplateOption(UUID id, String name, String language, String category, String headerFormat,
                                 String bodyText, int variableCount) {
        static TemplateOption of(MessageTemplate t) {
            return new TemplateOption(t.id(), t.name(), t.language(), t.category(), t.headerFormat(), t.bodyText(),
                    TemplateText.variables(t.bodyText()).size());
        }
    }

    /** A recipient: name, number (+ and digits), store, French status (with Meta's delivery), reason when not sent. */
    public record RecipientView(String clientName, String phone, String storeName, String status, String statusLabel,
                                String error) {
        static RecipientView of(Recipient r) {
            String status = "SENT".equals(r.status().name()) && r.deliveryStatus() != null
                    ? r.deliveryStatus() : r.status().name();
            return new RecipientView(r.clientName(), "+" + r.phone(), r.storeName(), status,
                    RecipientStatus.label(r.status().name(), r.deliveryStatus()), r.error());
        }
    }

    private final CampaignService campaigns;

    public WhatsAppCampaignController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    /** GET /api/v1/whatsapp/campaigns[?before=&beforeId=] — 50 newest first; next page = createdAt / id of the last. */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<CampaignView>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId) {
        String schema = TenantContext.requireCurrent();
        return ResponseEntity.ok(ApiResponseWrapper.ok(views(schema, campaigns.list(schema, before, beforeId))));
    }

    /** GET /api/v1/whatsapp/campaigns/templates — the approved templates of the owner a campaign can use. */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/templates")
    public ResponseEntity<ApiResponseWrapper<List<TemplateOption>>> templates() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(campaigns.usableTemplates(TenantContext.requireCurrent())
                .stream().map(TemplateOption::of).toList()));
    }

    /**
     * POST /api/v1/whatsapp/campaigns/preview-targeting {@code {storeIds?, inactiveForDays?, purchasedWithinDays?,
     * totalSpentMin?, totalSpentMax?, totalInvoicesMin?, totalInvoicesMax?}} → {@code {count, overLimit}}.
     */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ,
            readJustification = "compte les clients ciblés, sans rien enregistrer ni envoyer")
    @PostMapping("/preview-targeting")
    public ResponseEntity<ApiResponseWrapper<CampaignService.Preview>> preview(
            @RequestBody(required = false) JsonNode body) {
        if (body == null || body.isNull()) {
            return ResponseEntity.ok(ApiResponseWrapper.ok(campaigns.preview(TenantContext.requireCurrent(), null,
                    PurchaseCriteria.NONE)));
        }
        if (!body.isObject()) {
            throw new InvalidRequestParameterException("body", "object", "Requête invalide");
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(campaigns.preview(TenantContext.requireCurrent(),
                uuids(body.get("storeIds"), "storeIds"), criteria(body))));
    }

    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> get(@PathVariable UUID id) {
        String schema = TenantContext.requireCurrent();
        return ResponseEntity.ok(ApiResponseWrapper.ok(view(schema, campaigns.get(schema, id))));
    }

    /**
     * POST /api/v1/whatsapp/campaigns {@code {name, description?, storeIds?, templateId?, variables?, headerMediaId?,
     * headerMediaLink?, scheduledAt?}} + Story 21.11 purchase criteria ({@code inactiveForDays?, purchasedWithinDays?,
     * totalSpentMin?, totalSpentMax?, totalInvoicesMin?, totalInvoicesMax?}) → 201 DRAFT (SCHEDULED with
     * {@code scheduledAt}); 400 French.
     */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<CampaignView>> create(@RequestBody(required = false) JsonNode body) {
        String schema = TenantContext.requireCurrent();
        CampaignRow row = campaigns.create(schema, CurrentActor.actorId(), draft(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(view(schema, row)));
    }

    /** PATCH /api/v1/whatsapp/campaigns/{id} — the fields present change ({@code scheduledAt: null} unschedules). */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> update(@PathVariable UUID id,
                                                                   @RequestBody(required = false) JsonNode body) {
        String schema = TenantContext.requireCurrent();
        JsonNode b = body == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : body;
        Set<String> criteria = new HashSet<>();
        for (String field : PurchaseCriteria.FIELDS) {
            if (b.has(field)) {
                criteria.add(field);
            }
        }
        Presence p = new Presence(b.has("name"), b.has("description"), b.has("storeIds"), b.has("templateId"),
                b.has("variables"), b.has("headerMediaId") || b.has("headerMediaLink"), b.has("scheduledAt"),
                criteria);
        return ResponseEntity.ok(ApiResponseWrapper.ok(view(schema, campaigns.update(schema, CurrentActor.actorId(),
                id, draft(body), p))));
    }

    /** DELETE /api/v1/whatsapp/campaigns/{id} → 204; 409 {@code CAMPAIGN_ALREADY_SENT} once sending / sent. */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        campaigns.delete(TenantContext.requireCurrent(), CurrentActor.actorId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/whatsapp/campaigns/{id}/send → the campaign SENDING (messages leave in the background); 409
     * {@code CAMPAIGN_ALREADY_SENT} / {@code CAMPAIGN_SENDING_IN_PROGRESS} / {@code WHATSAPP_NOT_CONNECTED} /
     * {@code WHATSAPP_TEMPLATE_NOT_APPROVED}; 422 {@code CAMPAIGN_NOT_READY} / {@code CAMPAIGN_NO_RECIPIENT} /
     * {@code CAMPAIGN_TOO_MANY_RECIPIENTS}; 403 {@code QUOTA_EXCEEDED}.
     */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping("/{id}/send")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> send(@PathVariable UUID id) {
        String schema = TenantContext.requireCurrent();
        return ResponseEntity.ok(ApiResponseWrapper.ok(view(schema, campaigns.send(schema, CurrentActor.actorId(),
                id))));
    }

    /** POST /api/v1/whatsapp/campaigns/{id}/cancel — a SCHEDULED campaign → CANCELLED; 409 otherwise. */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponseWrapper<CampaignView>> cancel(@PathVariable UUID id) {
        String schema = TenantContext.requireCurrent();
        return ResponseEntity.ok(ApiResponseWrapper.ok(view(schema, campaigns.cancel(schema, CurrentActor.actorId(),
                id))));
    }

    /** GET /api/v1/whatsapp/campaigns/{id}/recipients[?after=phone] — 100 recipients by phone. */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}/recipients")
    public ResponseEntity<ApiResponseWrapper<List<RecipientView>>> recipients(
            @PathVariable UUID id, @RequestParam(required = false) String after) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(campaigns.recipients(TenantContext.requireCurrent(), id,
                after).stream().map(RecipientView::of).toList()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    private CampaignView view(String schema, CampaignRow row) {
        return CampaignView.of(row, campaigns.storeNames(schema, row.campaign().storeIds()));
    }

    private List<CampaignView> views(String schema, List<CampaignRow> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> ids.addAll(r.campaign().storeIds()));
        Map<UUID, String> names = campaigns.storeNames(schema, ids);
        return rows.stream().map(r -> CampaignView.of(r, names)).toList();
    }

    static Draft draft(JsonNode body) {
        if (body == null || body.isNull()) {
            return new Draft(null, null, null, null, null, null, null, null, PurchaseCriteria.NONE);
        }
        if (!body.isObject()) {
            throw new InvalidRequestParameterException("body", "object", "Requête invalide");
        }
        return new Draft(text(body, "name"), text(body, "description"), uuids(body.get("storeIds"), "storeIds"),
                uuid(body.get("templateId"), "templateId"), variables(body.get("variables")),
                text(body, "headerMediaId"), text(body, "headerMediaLink"), instant(body.get("scheduledAt")),
                criteria(body));
    }

    /**
     * Story 21.11 — the purchase criteria of a body (Bref spellings, flat). Days and counts: whole numbers (0 days =
     * not set, as Bref; a minimum of 0 = not set); amounts: numbers (whole francs: a minimum is rounded up, a maximum down; a minimum of 0 = not set).
     */
    static PurchaseCriteria criteria(JsonNode body) {
        Integer inactive = days(body, PurchaseCriteria.INACTIVE_FOR_DAYS);
        Integer within = days(body, PurchaseCriteria.PURCHASED_WITHIN_DAYS);
        Integer countMin = count(body, PurchaseCriteria.TOTAL_INVOICES_MIN);
        Long spentMin = amount(body, PurchaseCriteria.TOTAL_SPENT_MIN, true);
        Long spentMax = amount(body, PurchaseCriteria.TOTAL_SPENT_MAX, false);
        if (spentMin != null && spentMax != null && spentMin > spentMax
                && body.get(PurchaseCriteria.TOTAL_SPENT_MIN).decimalValue()
                .compareTo(body.get(PurchaseCriteria.TOTAL_SPENT_MAX).decimalValue()) <= 0) {
            // e.g. 0,3 .. 0,7: the bounds are in order but hold no whole amount
            throw new InvalidRequestParameterException(PurchaseCriteria.TOTAL_SPENT_MIN, "range",
                    "Aucun total d'achats en francs entiers n'est compris entre ces deux montants");
        }
        return new PurchaseCriteria(inactive, within, spentMin, spentMax,
                countMin == null || countMin == 0 ? null : countMin, // a minimum of 0 filters nothing
                count(body, PurchaseCriteria.TOTAL_INVOICES_MAX));
    }

    private static Integer days(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 0 || n.intValue() > PurchaseCriteria.MAX_DAYS) {
            throw new InvalidRequestParameterException(field, "range",
                    "Indiquez un nombre de jours entier entre 0 et 3 650 (0 : aucun critère)");
        }
        return n.intValue() == 0 ? null : n.intValue();
    }

    private static Long amount(JsonNode body, String field, boolean min) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isNumber() || (n.isFloatingPointNumber() && !Double.isFinite(n.doubleValue()))
                || n.decimalValue().signum() < 0
                || n.decimalValue().compareTo(java.math.BigDecimal.valueOf(PurchaseCriteria.MAX_AMOUNT)) > 0) {
            throw new InvalidRequestParameterException(field, "range",
                    "Indiquez un montant d'achats entre 0 et 1 000 000 000 000 FCFA");
        }
        // totals are whole francs: « ≥ 5 999,5 » is « ≥ 6 000 », « ≤ 6 000,7 » is « ≤ 6 000 »; a minimum of 0 filters nothing
        long v = n.decimalValue().setScale(0, min ? java.math.RoundingMode.CEILING : java.math.RoundingMode.FLOOR)
                .longValueExact();
        return min && v == 0 ? null : v;
    }

    private static Integer count(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 0
                || n.intValue() > PurchaseCriteria.MAX_COUNT) {
            throw new InvalidRequestParameterException(field, "range",
                    "Indiquez un nombre d'achats entier entre 0 et 10 000 000");
        }
        return n.intValue();
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new InvalidRequestParameterException(field, "type", "Valeur invalide");
        }
        return n.asText();
    }

    private static UUID uuid(JsonNode n, String field) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(n.asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException(field, "uuid", field.equals("templateId")
                    ? "Modèle de message inconnu" : "Boutique inconnue ou désactivée");
        }
    }

    private static List<UUID> uuids(JsonNode n, String field) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw new InvalidRequestParameterException(field, "type", "Liste de boutiques invalide");
        }
        if (n.size() > 100) {
            throw new InvalidRequestParameterException(field, "size", "100 boutiques au plus");
        }
        List<UUID> out = new ArrayList<>();
        n.forEach(e -> out.add(uuid(e, field)));
        return out;
    }

    private static List<VariableInput> variables(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray() || n.size() > 20) {
            throw new InvalidRequestParameterException("variables", "size", "20 variables au plus");
        }
        List<VariableInput> out = new ArrayList<>();
        for (JsonNode v : n) {
            if (!v.isObject()) {
                throw new InvalidRequestParameterException("variables", "type", "Variable invalide");
            }
            out.add(new VariableInput(v.path("source").isTextual() ? v.get("source").asText() : null,
                    v.path("value").isTextual() ? v.get("value").asText() : null));
        }
        return out;
    }

    private static Instant instant(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return Instant.parse(n.asText());
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(n.asText()).toInstant();
            } catch (DateTimeParseException e2) {
                throw new InvalidRequestParameterException("scheduledAt", "date",
                        "Date d'envoi invalide (format ISO 8601 attendu)");
            }
        }
    }
}
