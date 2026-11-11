package com.keevo.whatsappcloud.template.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
import com.keevo.whatsappcloud.template.application.WhatsAppTemplateService;
import com.keevo.whatsappcloud.template.domain.model.MessageTemplate;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.5 — the company's WhatsApp message templates (Bref {@code /v1/whatsapp/my-templates}, same shapes plus a
 * French {@code statusLabel}): owner only, module whatsapp-cloud (GET → READ, others → WRITE). A template is DRAFT
 * until the number is connected, then follows Meta's review.
 */
@RestController
@RequestMapping("/api/v1/whatsapp/my-templates")
@PreAuthorize("hasRole('OWNER')")
public class WhatsAppTemplateController {

    /** Bref's {@code CreateTemplatePayload} (media header: {@code headerHandle} from {@code POST /media}). */
    public record CreateRequest(String name, String category, String language, String headerFormat,
                                String headerText, String headerHandle, String bodyText,
                                @Size(max = 20, message = "20 exemples au plus") List<String> bodyExamples,
                                String footerText) {
    }

    /** Bref's {@code OwnerTemplate} (Meta's template shape) + {@code statusLabel}. */
    public record TemplateView(UUID id, String name, String status, String statusLabel, String category,
                               String language, List<Map<String, Object>> components,
                               @JsonProperty("rejected_reason") String rejectedReason, String submitError) {
        static TemplateView of(MessageTemplate t) {
            List<Map<String, Object>> components = new ArrayList<>();
            if ("TEXT".equals(t.headerFormat()) && t.headerText() != null) {
                components.add(Map.of("type", "HEADER", "format", "TEXT", "text", t.headerText()));
            } else if (!"NONE".equals(t.headerFormat())) {
                components.add(Map.of("type", "HEADER", "format", t.headerFormat()));
            }
            components.add(Map.of("type", "BODY", "text", t.bodyText()));
            if (t.footerText() != null) {
                components.add(Map.of("type", "FOOTER", "text", t.footerText()));
            }
            return new TemplateView(t.id(), t.name(), t.status(), label(t.status()), t.category(), t.language(),
                    components, t.rejectedReason(), t.submitError());
        }
    }

    public record MediaView(String headerHandle) {
    }

    private final WhatsAppTemplateService templates;

    public WhatsAppTemplateController(WhatsAppTemplateService templates) {
        this.templates = templates;
    }

    /** GET /api/v1/whatsapp/my-templates — the owner's templates, newest first, with Meta's review status. */
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<TemplateView>>> list() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(templates.list(TenantContext.requireCurrent()).stream()
                .map(TemplateView::of).toList()));
    }

    /**
     * POST /api/v1/whatsapp/my-templates → 201 the template (DRAFT, or PENDING when submitted to Meta at once); 409
     * {@code WHATSAPP_TEMPLATE_NAME_TAKEN} / {@code WHATSAPP_TEMPLATE_LIMIT}; 400 French.
     */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponseWrapper<TemplateView>> create(@RequestBody CreateRequest r) {
        if (r.bodyExamples() != null && r.bodyExamples().size() > 20) {
            throw new InvalidRequestParameterException("bodyExamples", "size", "20 exemples au plus");
        }
        MessageTemplate t = templates.create(TenantContext.requireCurrent(), CurrentActor.actorId(),
                new WhatsAppTemplateService.NewTemplate(r.name(), r.category(), r.language(), r.headerFormat(),
                        r.headerText(), r.headerHandle(), r.bodyText(), r.bodyExamples(), r.footerText()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(TemplateView.of(t)));
    }

    /** DELETE /api/v1/whatsapp/my-templates/{id} → 204 (also deleted at Meta when submitted); 404. */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        templates.delete(TenantContext.requireCurrent(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/whatsapp/my-templates/media (multipart {@code file}: JPEG / PNG ≤ 5 MB, MP4 / PDF ≤ 10 MB) — the
     * example media of an IMAGE / VIDEO / DOCUMENT header, sent to Meta → {@code {headerHandle}}; 409
     * {@code WHATSAPP_NOT_CONNECTED}.
     */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<MediaView>> media(@RequestParam("file") MultipartFile file)
            throws IOException {
        com.keevo.whatsappcloud.inbox.adapter.in.rest.MediaUploads.Media media =
                com.keevo.whatsappcloud.inbox.adapter.in.rest.MediaUploads.check(file);
        String handle = templates.uploadHeaderSample(TenantContext.requireCurrent(), media.content(), media.type(),
                media.filename());
        return ResponseEntity.ok(ApiResponseWrapper.ok(new MediaView(handle)));
    }

    /** French label of a status (never the raw code on screen). */
    static String label(String status) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("DRAFT", "Brouillon");
        labels.put("PENDING", "En cours de validation");
        labels.put("APPROVED", "Approuvé");
        labels.put("REJECTED", "Refusé");
        labels.put("PAUSED", "En pause");
        labels.put("DISABLED", "Désactivé");
        return labels.getOrDefault(status, "En cours de validation");
    }
}
