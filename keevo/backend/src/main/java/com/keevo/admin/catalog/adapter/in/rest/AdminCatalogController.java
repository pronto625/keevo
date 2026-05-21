package com.keevo.admin.catalog.adapter.in.rest;

import com.keevo.admin.catalog.adapter.in.rest.dto.AdminCatalogSummaryDto;
import com.keevo.admin.catalog.adapter.in.rest.dto.AdminProductPageResponse;
import com.keevo.admin.catalog.application.service.AdminCatalogService;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import com.keevo.admin.catalog.domain.port.in.ListProductsQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collection;

@Tag(name = "Admin — Catalog", description = "Super Admin cross-tenant product catalog monitoring")
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
        requireSuperAdmin();
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

        requireSuperAdmin();

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

        requireSuperAdmin();

        ListProductsQuery query = new ListProductsQuery(
                search, tenantId, stockLevel, status, plan,
                0, Integer.MAX_VALUE);

        Page<AdminProductListItem> allProducts = adminCatalogService.execute(query);

        StringBuilder csv = new StringBuilder();
        csv.append("product_id,product_name,tenant_name,plan,category,price_xaf,stock_quantity,status,last_updated\n");

        for (AdminProductListItem item : allProducts.getContent()) {
            csv.append(escapeCsv(item.id())).append(',')
               .append(escapeCsv(item.name())).append(',')
               .append(escapeCsv(item.tenantName())).append(',')
               .append(escapeCsv(item.tenantPlan())).append(',')
               .append(escapeCsv(item.categoryName() != null ? item.categoryName() : "")).append(',')
               .append(item.price()).append(',')
               .append(item.stockQuantity()).append(',')
               .append(escapeCsv(item.status())).append(',')
               .append(item.updatedAt() != null ? item.updatedAt().toString() : "")
               .append('\n');
        }

        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "keevo-products-" + LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(bytes);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void requireSuperAdmin() {
        Collection<? extends GrantedAuthority> authorities =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        boolean isSuperAdmin = authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        if (!isSuperAdmin) {
            throw new DomainException(ErrorCode.FORBIDDEN, "SUPER_ADMIN role required");
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
