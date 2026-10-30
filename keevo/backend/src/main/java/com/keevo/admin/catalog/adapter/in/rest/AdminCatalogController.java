package com.keevo.admin.catalog.adapter.in.rest;

import com.keevo.shared.infrastructure.security.CurrentActor;
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
               security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/products/export")
    public ResponseEntity<byte[]> exportProducts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String stockLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String plan) {

        CurrentActor.requireSuperAdmin();

        ListProductsQuery query = new ListProductsQuery(
                search, tenantId, stockLevel, status, plan,
                0, Integer.MAX_VALUE);

        Page<AdminProductListItem> allProducts = adminCatalogService.execute(query);

        byte[] bytes = ProductsCsvWriter.write(allProducts.getContent());
        String filename = ProductsCsvWriter.filename(ProductsCsvWriter.today());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(bytes);
    }
}
