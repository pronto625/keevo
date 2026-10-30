package com.keevo.reporting.export.adapter.in.rest;

import com.keevo.reporting.export.adapter.in.rest.dto.ExportDatasetDto;
import com.keevo.reporting.export.adapter.in.rest.dto.ExportJobDto;
import com.keevo.reporting.export.adapter.in.rest.dto.ExportRequestDto;
import com.keevo.reporting.export.application.service.ExportDatasetRegistry;
import com.keevo.reporting.export.application.service.ExportCriteria;
import com.keevo.reporting.export.application.service.ExportRendererRegistry;
import com.keevo.reporting.export.domain.port.out.DocumentExportRenderer;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.model.ExportRequest;
import com.keevo.reporting.export.domain.port.in.DownloadExportUseCase;
import com.keevo.reporting.export.domain.port.in.ListExportsUseCase;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ExportController — server-side data export (Story v1s-18-1, FR55/FR94/FR96). OWNER only.
 *
 * <pre>
 * GET  /api/v1/exports/datasets   → exportable datasets and their columns
 * POST /api/v1/exports            → 200 file (direct) | 202 job (background, Premium)
 * GET  /api/v1/exports            → « Mes exports » (background jobs, newest first)
 * GET  /api/v1/exports/{id}       → one job
 * GET  /api/v1/exports/{id}/file  → file of a READY job (410 expired, 409 not ready)
 * </pre>
 *
 * <p>A direct export is written to the {@link HttpServletResponse} on the request thread — a
 * {@code StreamingResponseBody} would run on another thread and lose {@code TenantContext}.
 */
@Tag(name = "Exports", description = "Export de données (CSV, Excel, PDF) — propriétaire uniquement")
@RestController
@RequestMapping("/api/v1/exports")
@PreAuthorize("hasRole('OWNER')")
public class ExportController {

    private static final String OWNER_ONLY = "Only OWNER can export data";
    static final int MAX_DATASETS = 20;

    private final RequestExportUseCase requestExportUseCase;
    private final ListExportsUseCase listExportsUseCase;
    private final DownloadExportUseCase downloadExportUseCase;
    private final ExportDatasetRegistry datasetRegistry;
    private final ExportRendererRegistry rendererRegistry;

    public ExportController(RequestExportUseCase requestExportUseCase,
                            ListExportsUseCase listExportsUseCase,
                            DownloadExportUseCase downloadExportUseCase,
                            ExportDatasetRegistry datasetRegistry,
                            ExportRendererRegistry rendererRegistry) {
        this.requestExportUseCase = requestExportUseCase;
        this.listExportsUseCase = listExportsUseCase;
        this.downloadExportUseCase = downloadExportUseCase;
        this.datasetRegistry = datasetRegistry;
        this.rendererRegistry = rendererRegistry;
    }

    @Operation(summary = "Jeux de données exportables")
    @RequiresEntitlement(value = "reporting", access = RequiresEntitlement.Access.READ)
    @GetMapping("/datasets")
    public ResponseEntity<ApiResponseWrapper<List<ExportDatasetDto>>> datasets() {
        CurrentActor.requireOwner(OWNER_ONLY);
        // Story v1s-18-3 — the PDF document made from each dataset, if any (app template list).
        var pdf = rendererRegistry.find(ExportFormat.PDF)
                .filter(DocumentExportRenderer.class::isInstance)
                .map(DocumentExportRenderer.class::cast);
        return ResponseEntity.ok(ApiResponseWrapper.ok(datasetRegistry.all().stream()
                .map(p -> ExportDatasetDto.from(p, pdf.flatMap(r -> r.templateLabel(p.key())).orElse(null)))
                .toList()));
    }

    @Operation(summary = "Lancer un export (fichier direct ou tâche en arrière-plan)")
    @RequiresEntitlement(value = "reporting", access = RequiresEntitlement.Access.READ,
            readJustification = "produit un fichier à partir des données existantes, sans les modifier")
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<ExportJobDto>> request(@RequestBody(required = false) ExportRequestDto body,
                                                                    HttpServletResponse response) throws IOException {
        CurrentActor.requireOwner(OWNER_ONLY);
        ExportRequest request = parse(body);
        checkCriteriaApply(request);
        String tenant = TenantContext.getCurrentTenant();
        RequestExportUseCase.ExportOutcome outcome;
        try {
            outcome = requestExportUseCase.request(
                    tenant, CurrentActor.actorId(), request, (contentType, fileName) -> {
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentType(contentType);
                        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment(fileName));
                        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                        return response.getOutputStream();
                    });
        } catch (IOException | RuntimeException e) {
            // Not committed: drop the file headers so the JSON error is not sent as an attachment.
            // Committed: nothing can be fixed — rethrowing aborts the (chunked) stream.
            if (!response.isCommitted()) {
                response.reset();
            }
            throw e;
        }
        if (outcome instanceof RequestExportUseCase.Background bg) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponseWrapper.ok(ExportJobDto.from(bg.job(), null)));
        }
        response.flushBuffer();
        return null; // direct: the file is already written to the response
    }

    @Operation(summary = "Mes exports")
    @RequiresEntitlement(value = "reporting", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<ExportJobDto>>> list() {
        CurrentActor.requireOwner(OWNER_ONLY);
        String tenant = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                listExportsUseCase.list().stream().map(j -> toDto(tenant, j)).toList()));
    }

    @Operation(summary = "Détail d'un export")
    @RequiresEntitlement(value = "reporting", access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ExportJobDto>> get(@PathVariable UUID id) {
        CurrentActor.requireOwner(OWNER_ONLY);
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                toDto(TenantContext.getCurrentTenant(), listExportsUseCase.get(id))));
    }

    @Operation(summary = "Télécharger le fichier d'un export prêt")
    @RequiresEntitlement(value = "reporting", access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable UUID id) throws IOException {
        CurrentActor.requireOwner(OWNER_ONLY);
        return fileResponse(downloadExportUseCase.open(id));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ExportJobDto toDto(String tenant, ExportJob job) {
        String url = job.getStatus() == ExportJobStatus.READY
                ? downloadExportUseCase.signedDownloadPath(tenant, job.getId(), job.getExpiresAt())
                : null;
        return ExportJobDto.from(job, url);
    }

    static ResponseEntity<Resource> fileResponse(DownloadExportUseCase.ExportFile file) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(file.fileName()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (file.size() >= 0) builder.contentLength(file.size());
        return builder.body(new InputStreamResource(file.content()));
    }

    static String attachment(String fileName) {
        return ContentDisposition.attachment().filename(fileName).build().toString(); // names are ASCII
    }

    /** Request-shape validation → 400 VALIDATION_ERROR. Unknown dataset keys are the service's 400. */
    static ExportRequest parse(ExportRequestDto body) {
        if (body == null) throw new InvalidRequestParameterException("body", "Request body is required");
        if (body.datasets() == null || body.datasets().isEmpty()) {
            throw new InvalidRequestParameterException("datasets", "datasets must not be empty");
        }
        if (body.datasets().size() > MAX_DATASETS) {
            throw new InvalidRequestParameterException("datasets", "at most " + MAX_DATASETS + " datasets");
        }
        Set<String> seen = new HashSet<>();
        for (String key : body.datasets()) {
            if (key == null || key.isBlank()) {
                throw new InvalidRequestParameterException("datasets", "blank dataset key");
            }
            if (!seen.add(key)) {
                throw new InvalidRequestParameterException("datasets", "duplicate dataset key");
            }
        }
        if (body.format() == null || body.format().isBlank()) {
            throw new InvalidRequestParameterException("format", "format is required");
        }
        ExportFormat format;
        try {
            format = ExportFormat.valueOf(body.format().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException("format", "unknown format");
        }
        UUID storeId = parseUuid("storeId", body.storeId());
        Instant from = parseInstant("from", body.from());
        Instant to = parseInstant("to", body.to());
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from must not be after to");
        }
        Map<String, String> criteria = criteria(body);
        return new ExportRequest(body.datasets(), format, new ExportFilter(storeId, from, to, criteria));
    }

    /** Story v1s-18-2 — value shape here; applicability is checked against the requested datasets. */
    private static Map<String, String> criteria(ExportRequestDto body) {
        try {
            return ExportCriteria.normalize(body.criteria());
        } catch (ExportCriteria.InvalidCriterionException e) {
            throw new InvalidRequestParameterException(e.getField(), e.getMessage());
        }
    }

    /** Unknown datasets are left to the service (400 EXPORT_DATASET_UNKNOWN). */
    private void checkCriteriaApply(ExportRequest request) {
        try {
            ExportCriteria.checkApplicable(request.filter().criteria(), request.datasets().stream()
                    .map(datasetRegistry::find).flatMap(java.util.Optional::stream).toList());
        } catch (ExportCriteria.InvalidCriterionException e) {
            throw new InvalidRequestParameterException(e.getField(), e.getMessage());
        }
    }

    private static UUID parseUuid(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException(name, "invalid UUID");
        }
    }

    private static Instant parseInstant(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestParameterException(name, "invalid ISO-8601 instant");
        }
    }
}
