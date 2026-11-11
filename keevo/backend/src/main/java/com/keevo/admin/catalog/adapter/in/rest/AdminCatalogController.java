package com.keevo.admin.catalog.adapter.in.rest;

import com.keevo.shared.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletResponse;
import com.keevo.admin.catalog.adapter.in.rest.dto.AdminCatalogSummaryDto;
import com.keevo.admin.catalog.adapter.in.rest.dto.AdminProductPageResponse;
import com.keevo.admin.catalog.application.service.AdminCatalogService;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import com.keevo.admin.catalog.domain.port.in.ListProductsQuery;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@Tag(name = "Admin — Catalog", description = "Super Admin cross-tenant product catalog monitoring")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/catalog")
public class AdminCatalogController {

    private final AdminCatalogService adminCatalogService;

    public AdminCatalogController(AdminCatalogService adminCatalogService) {
        this.adminCatalogService = adminCatalogService;
    }

    @Operation(summary = "Get platform-wide catalog summary (KPI cards)",
               security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/summary")
    public ResponseEntity<ApiResponseWrapper<AdminCatalogSummaryDto>> getCatalogSummary() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                AdminCatalogSummaryDto.from(adminCatalogService.execute())));
    }

    @Operation(summary = "List all products across tenants (paginated)",
               security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/products")
    public ResponseEntity<ApiResponseWrapper<AdminProductPageResponse>> listProducts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String stockLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String plan,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {

        CurrentActor.requireSuperAdmin();

        ListProductsQuery query = new ListProductsQuery(
                search, tenantId, stockLevel, status, plan,
                page, Math.min(pageSize, 100));

        Page<AdminProductListItem> result = adminCatalogService.execute(query);
        return ResponseEntity.ok(ApiResponseWrapper.ok(AdminProductPageResponse.from(result)));
    }

    @Operation(summary = "Export all products as CSV with current filters",
               security = @SecurityRequirement(name = "bearerAuth"),
               responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                       description = "Fichier CSV (UTF-8 avec BOM, séparateur ;)",
                       content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "text/csv")))
    @GetMapping("/products/export")
    public void exportProducts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String stockLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String plan,
            HttpServletResponse response) throws java.io.IOException {

        CurrentActor.requireSuperAdmin();

        ListProductsQuery query = new ListProductsQuery(
                search, tenantId, stockLevel, status, plan,
                0, Integer.MAX_VALUE);

        // Story 19.11c: prepared first (a filter error is still a JSON error, nothing written yet), then streamed
        // to the response on the request thread, a few companies at a time — same columns and labels as before,
        // rows grouped by company (name order), most recently updated first inside a company.
        AdminCatalogService.ProductExport export = adminCatalogService.prepareExport(query);
        String filename = ProductsCsvWriter.filename(ProductsCsvWriter.today());

        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        response.setHeader(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8");
        java.io.Writer out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        ProductsCsvWriter.writeHeader(out);
        try {
            export.forEach(item -> ProductsCsvWriter.writeRow(out, item));
        } catch (RuntimeException e) {
            // The response may already be committed (200 + part of the rows): never let a cut file look complete.
            ProductsCsvWriter.writeInterrupted(out);
            out.flush();
            throw e;
        }
        out.flush();
    }
}
