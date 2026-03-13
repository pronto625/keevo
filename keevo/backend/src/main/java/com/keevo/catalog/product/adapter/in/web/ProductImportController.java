package com.keevo.catalog.product.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.adapter.in.web.dto.CsvColumnMappingRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.CreateDraftProductRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.ImportResultResponseDto;
import com.keevo.catalog.product.application.dto.CsvColumnMapping;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.GenerateCsvTemplateUseCase;
import com.keevo.catalog.product.application.usecase.ImportCsvProductsUseCase;
import com.keevo.catalog.product.adapter.in.web.dto.ProductResponseDto;
import com.keevo.messaging.notification.application.usecase.GetPendingDraftsCountUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * ProductImportController — HTTP entry points for CSV import and progressive product creation.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/v1/products/import/template} — download CSV template (AC1)</li>
 *   <li>{@code POST /api/v1/products/import}          — multipart upload, returns ImportResult (AC2–AC4, AC8)</li>
 *   <li>{@code POST /api/v1/products/draft}           — create DRAFT product (AC5)</li>
 *   <li>{@code GET  /api/v1/products/drafts/count}    — badge counter for owner (AC6)</li>
 * </ul>
 *
 * <p>RBAC is enforced inside the use cases (not via {@code @PreAuthorize}) to align with
 * the established project convention (see story 1.4 post-mortem notes).
 *
 * <p>Story 2.4.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductImportController {

    private final ImportCsvProductsUseCase    importUseCase;
    private final CreateDraftProductUseCase   draftUseCase;
    private final GenerateCsvTemplateUseCase  templateUseCase;
    private final GetPendingDraftsCountUseCase pendingCountUseCase;
    private final ObjectMapper                objectMapper;

    public ProductImportController(
            ImportCsvProductsUseCase importUseCase,
            CreateDraftProductUseCase draftUseCase,
            GenerateCsvTemplateUseCase templateUseCase,
            GetPendingDraftsCountUseCase pendingCountUseCase,
            ObjectMapper objectMapper) {
        this.importUseCase      = importUseCase;
        this.draftUseCase       = draftUseCase;
        this.templateUseCase    = templateUseCase;
        this.pendingCountUseCase = pendingCountUseCase;
        this.objectMapper       = objectMapper;
    }

    // ── AC1: Template download ────────────────────────────────────────────────

    /**
     * GET /api/v1/products/import/template
     * Returns a UTF-8 CSV file with the expected column layout + 3 example rows.
     */
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] csv = templateUseCase.execute();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"keevo-produits-template.csv\"")
                .body(csv);
    }

    // ── AC2–AC4, AC8: CSV import ──────────────────────────────────────────────

    /**
     * POST /api/v1/products/import
     * Multipart: {@code file} (CSV) + {@code mapping} (JSON string of CsvColumnMappingRequestDto).
     *
     * <p>Example curl (see keevo/scripts/curl-tests-story-2-4.sh):
     * <pre>{@code
     *   curl -X POST .../import \
     *     -H "Authorization: Bearer <token>" \
     *     -F "file=@products.csv" \
     *     -F 'mapping={"nameColumn":"nom","priceColumn":"prix_vente"}'
     * }</pre>
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<ImportResultResponseDto>> importCsv(
            @RequestPart("file") MultipartFile file,
            @RequestPart("mapping") String mappingJson) throws IOException {

        // Parse mapping JSON
        CsvColumnMappingRequestDto mappingRequest = parseMappingJson(mappingJson);

        // Validate required columns (manual validation — @Valid doesn't work on @RequestPart String)
        if (mappingRequest.nameColumn() == null || mappingRequest.nameColumn().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponseWrapper.error(
                            "Le nom de la colonne 'nom' est obligatoire",
                            "BAD_REQUEST", ErrorCode.VALIDATION_ERROR.name(), null));
        }
        if (mappingRequest.priceColumn() == null || mappingRequest.priceColumn().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponseWrapper.error(
                            "Le nom de la colonne 'prix_vente' est obligatoire",
                            "BAD_REQUEST", ErrorCode.VALIDATION_ERROR.name(), null));
        }

        UUID actorId   = extractActorId();
        String actorRole = extractRole();

        var mapping = new CsvColumnMapping(
                mappingRequest.nameColumn(),
                mappingRequest.priceColumn(),
                mappingRequest.buyPriceColumn(),
                mappingRequest.transportCostColumn(),
                mappingRequest.categoryColumn(),
                mappingRequest.skuColumn(),
                mappingRequest.quantityColumn(),
                mappingRequest.thresholdColumn()
        );

        var command = new ImportCsvProductsUseCase.ImportCsvCommand(
                file.getInputStream(),
                mapping,
                actorId,
                actorRole,
                actorId.toString()   // actorName — fallback to UUID until user-profile API exists
        );

        var result = importUseCase.execute(command);
        return ResponseEntity.ok(ApiResponseWrapper.ok(ImportResultResponseDto.from(result)));
    }

    // ── AC5: Create draft product ──────────────────────────────────────────────

    /**
     * POST /api/v1/products/draft
     * Creates a product with status=DRAFT. Accessible to OWNER and EMPLOYEE.
     */
    @PostMapping("/draft")
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> createDraft(
            @Valid @RequestBody CreateDraftProductRequestDto request) {

        UUID actorId   = extractActorId();
        String actorRole = extractRole();

        var command = new CreateDraftProductUseCase.CreateDraftCommand(
                request.name(),
                request.description(),
                request.categoryId(),
                request.price(),
                request.buyPrice(),
                request.transportCost(),
                actorId,
                actorRole,
                actorId.toString()  // actorName fallback
        );

        var saved = draftUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(ProductResponseDto.fromDomain(saved)));
    }

    // ── AC6: Pending drafts counter ────────────────────────────────────────────

    /**
     * GET /api/v1/products/drafts/count
     * Returns the number of unacknowledged DRAFT products for the current tenant.
     * Used by the owner's UI badge counter.
     */
    @GetMapping("/drafts/count")
    public ResponseEntity<ApiResponseWrapper<Long>> getPendingDraftsCount() {
        String actorRole = extractRole();
        long count = pendingCountUseCase.execute(actorRole);
        return ResponseEntity.ok(ApiResponseWrapper.ok(count));
    }

    // ── Private helpers ─────────────────────────────────────────────────────── 

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private String extractRole() {
        return SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN,
                        "Rôle introuvable dans le jeton d'authentification"));
    }

    private CsvColumnMappingRequestDto parseMappingJson(String json) {
        try {
            return objectMapper.readValue(json, CsvColumnMappingRequestDto.class);
        } catch (JsonProcessingException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Le champ 'mapping' n'est pas un JSON valide: " + e.getOriginalMessage());
        }
    }
}
