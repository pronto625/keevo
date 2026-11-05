package com.keevo.catalog.product.adapter.in.web;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.crm.api.SupplierQueryPort;
import com.keevo.catalog.product.adapter.in.web.dto.CreateProductRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.UpdateProductRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.ProductResponseDto;
import com.keevo.catalog.product.adapter.in.web.dto.ProductSupplierResponseDto;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateOrRestoreProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.GetProductPricingUseCase;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.store.api.StoreQueryPort;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * Product REST Controller
 * 
 * Handles CRUD operations for products with JWT authentication and tenant isolation
 */
@RestController
@RequestMapping("/api/v1/products")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class ProductController {

    private final CreateOrRestoreProductUseCase createOrRestoreProductUseCase; // 17.13: archived name → restored
    private final UpdateProductUseCase updateProductUseCase;
    private final ArchiveProductUseCase archiveProductUseCase;
    private final ProductRepository productRepository;
    private final GetProductPricingUseCase getProductPricingUseCase;
    private final SupplierQueryPort supplierQueryPort; // 12.2a: crm through its public API
    private final StoreQueryPort storeQuery; // Story 14.10: resolve store names

    public ProductController(CreateOrRestoreProductUseCase createOrRestoreProductUseCase,
                           UpdateProductUseCase updateProductUseCase,
                           ArchiveProductUseCase archiveProductUseCase,
                           ProductRepository productRepository,
                           GetProductPricingUseCase getProductPricingUseCase,
                           SupplierQueryPort supplierQueryPort,
                           StoreQueryPort storeQuery) {
        this.createOrRestoreProductUseCase = createOrRestoreProductUseCase;
        this.updateProductUseCase = updateProductUseCase;
        this.archiveProductUseCase = archiveProductUseCase;
        this.productRepository = productRepository;
        this.getProductPricingUseCase = getProductPricingUseCase;
        this.supplierQueryPort = supplierQueryPort;
        this.storeQuery = storeQuery;
    }

    /**
     * Create new product
     * POST /api/v1/products
     *
     * <p>Story 17.13 — a name held by an ARCHIVED product restores that product instead (200, {@code restored: true},
     * see {@link CreateOrRestoreProductUseCase}); nothing of the request is applied to it — in particular
     * {@code stockQuantity}, which never creates stock here anyway (legacy column only, 17-11), is ignored.
     */
    @RequiresEntitlement("catalog")
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> createProduct(
            @Valid @RequestBody CreateProductRequestDto request) {
        
        UUID actorId = CurrentActor.actorId();
        String actorRole = CurrentActor.role();
        String actorName = CurrentActor.actorLabel();
        String storeName = resolveEmployeeStoreName();
        
        var dto = new CreateProductUseCase.CreateProductDto(
                request.name(),
                request.description(),
                request.sku(),
                request.categoryId(),
                request.price(),
                request.buyPrice(),
                request.transportCost(),
                request.stockQuantity(),
                actorId,
                0,
                request.status(),
                actorRole,
                actorName,
                storeName,
                null, // clientId — online direct creation always generates the id server-side
                request.trackStock() // Story 17.12 — null → tracked
        );
        
        // Story 17.13: a name held by an ARCHIVED product restores it (limit-checked, existing fields kept) → 200
        // with the existing product and restored = true; a draft creation whose restore is refused gets the archived
        // product as is (200, no marker); a visible product's name keeps the 409.
        var outcome = createOrRestoreProductUseCase.execute(dto);
        if (outcome.restored()) {
            return ResponseEntity.ok(ApiResponseWrapper.ok(ProductResponseDto.fromDomain(outcome.product(), true)));
        }
        if (outcome.merged()) {
            return ResponseEntity.ok(ApiResponseWrapper.ok(ProductResponseDto.fromDomain(outcome.product())));
        }
        ProductResponseDto response = ProductResponseDto.fromDomain(outcome.product());
        
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(response));
    }

    /**
     * List all active products
     * GET /api/v1/products
     */
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<ProductResponseDto>>> listProducts() {
        List<Product> products = productRepository.findAllActive();
        List<ProductResponseDto> response = products.stream()
                .map(ProductResponseDto::fromDomain)
                .toList();
        
        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * Get product by ID
     * GET /api/v1/products/{id}
     */
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> getProduct(@PathVariable UUID id) {
        return productRepository.findById(id)
                .map(product -> {
                    ProductResponseDto response = ProductResponseDto.fromDomain(product);
                    return ResponseEntity.ok(ApiResponseWrapper.ok(response));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponseWrapper.error("Produit introuvable", "NOT_FOUND", "PRODUCT_NOT_FOUND", null)));
    }

    /**
     * Update product
     * PATCH /api/v1/products/{id}
     */
    @RequiresEntitlement("catalog")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequestDto request) {
        
        UUID actorId = CurrentActor.actorId();
        
        var dto = new UpdateProductUseCase.UpdateProductDto(
                id,
                request.name(),
                request.description(),
                request.sku(),
                request.categoryId(),
                request.price(),
                request.buyPrice(),
                request.transportCost(),
                request.stockQuantity(),
                actorId,
                CurrentActor.role(),  // v1s-12-10: real role → Story 2.4 draft guard (FORBIDDEN → 403)
                request.trackStock()  // Story 17.12 — null → unchanged; a change is OWNER-only
        );
        
        // Domain errors (404 PRODUCT_NOT_FOUND, 422 VALIDATION_ERROR) go through the global
        // handler: catching IllegalArgumentException here labelled every validation error
        // « Produit introuvable ».
        Product product = updateProductUseCase.execute(dto);
        return ResponseEntity.ok(ApiResponseWrapper.ok(ProductResponseDto.fromDomain(product)));
    }

    /**
     * Archive product (soft delete)
     * PATCH /api/v1/products/{id}/archive
     */
    @RequiresEntitlement("catalog")
    @PatchMapping("/{id}/archive")
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> archiveProduct(@PathVariable UUID id) {
        UUID actorId = CurrentActor.actorId();
        
        var dto = new ArchiveProductUseCase.ArchiveProductDto(id, actorId);
        archiveProductUseCase.execute(dto);
        // Return the archived product so the caller sees archived=true
        return productRepository.findById(id)
                .map(product -> ResponseEntity.ok(ApiResponseWrapper.ok(ProductResponseDto.fromDomain(product))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponseWrapper.error("Produit introuvable", "NOT_FOUND", "PRODUCT_NOT_FOUND", null)));
    }

    /**
     * Get supplier linked to a product (AC5 — Story 2.5)
     * GET /api/v1/products/{productId}/supplier
     *
     * <p>Returns the supplier that supplies this product (from {@code product_suppliers}).
     * Returns 404 if no supplier is linked.
     */
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping("/{productId}/supplier")
    public ResponseEntity<ApiResponseWrapper<ProductSupplierResponseDto>> getProductSupplier(
            @PathVariable UUID productId) {
        return supplierQueryPort.findByProductId(TenantContext.getCurrentTenant(), productId)
                .map(supplier -> ResponseEntity.ok(
                        ApiResponseWrapper.ok(ProductSupplierResponseDto.from(supplier))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponseWrapper.error(
                                "Aucun fournisseur lié à ce produit",
                                "NOT_FOUND", "SUPPLIER_NOT_FOUND", null)));
    }

    /**
     * Get product pricing / margin calculation
     * GET /api/v1/products/{id}/pricing
     *
     * <p>Returns real-time margin calculation without modifying the product.
     * Used by the frontend for live margin display in the product form.
     */
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}/pricing")
    public ResponseEntity<ApiResponseWrapper<GetProductPricingUseCase.ProductPricingDto>> getProductPricing(
            @PathVariable UUID id) {
        try {
            var pricing = getProductPricingUseCase.execute(id);
            return ResponseEntity.ok(ApiResponseWrapper.ok(pricing));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseWrapper.error("Produit introuvable", "NOT_FOUND", "PRODUCT_NOT_FOUND", null));
        }
    }

    // ── Story 14.10 helpers ─────────────────────────────────────────────

    /** Resolve employee's assigned store name, or null for OWNER. */
    private String resolveEmployeeStoreName() {
        if (CurrentActor.authentication() != null
                && CurrentActor.authentication().getDetails() instanceof AuthDetails ad && ad.storeId() != null) {
            return storeQuery.findById(TenantContext.getCurrentTenant(), ad.storeId())
                    .map(s -> s.name())
                    .orElse(null);
        }
        return null;
    }
}