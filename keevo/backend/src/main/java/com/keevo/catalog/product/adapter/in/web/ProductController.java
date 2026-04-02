package com.keevo.catalog.product.adapter.in.web;

import com.keevo.catalog.contact.adapter.in.web.dto.SupplierResponseDto;
import com.keevo.catalog.contact.application.usecase.GetSupplierByProductUseCase;
import com.keevo.catalog.contact.application.usecase.GetSupplierProfileUseCase;
import com.keevo.catalog.product.adapter.in.web.dto.CreateProductRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.UpdateProductRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.ProductResponseDto;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.GetProductPricingUseCase;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;

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
@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
public class ProductController {

    private final CreateProductUseCase createProductUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final ArchiveProductUseCase archiveProductUseCase;
    private final ProductRepository productRepository;
    private final GetProductPricingUseCase getProductPricingUseCase;
    private final GetSupplierByProductUseCase getSupplierByProductUseCase;
    private final GetSupplierProfileUseCase getSupplierProfileUseCase;

    public ProductController(CreateProductUseCase createProductUseCase,
                           UpdateProductUseCase updateProductUseCase,
                           ArchiveProductUseCase archiveProductUseCase,
                           ProductRepository productRepository,
                           GetProductPricingUseCase getProductPricingUseCase,
                           GetSupplierByProductUseCase getSupplierByProductUseCase,
                           GetSupplierProfileUseCase getSupplierProfileUseCase) {
        this.createProductUseCase = createProductUseCase;
        this.updateProductUseCase = updateProductUseCase;
        this.archiveProductUseCase = archiveProductUseCase;
        this.productRepository = productRepository;
        this.getProductPricingUseCase = getProductPricingUseCase;
        this.getSupplierByProductUseCase = getSupplierByProductUseCase;
        this.getSupplierProfileUseCase = getSupplierProfileUseCase;
    }

    /**
     * Create new product
     * POST /api/v1/products
     */
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> createProduct(
            @Valid @RequestBody CreateProductRequestDto request) {
        
        // Extract actorId from SecurityContext — set by JwtAuthFilter
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        
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
                request.status()
        );
        
        Product product = createProductUseCase.execute(dto);
        ProductResponseDto response = ProductResponseDto.fromDomain(product);
        
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(response));
    }

    /**
     * List all active products
     * GET /api/v1/products
     */
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
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequestDto request) {
        
        // Extract actorId from SecurityContext — set by JwtAuthFilter
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        
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
                actorId
        );
        
        try {
            Product product = updateProductUseCase.execute(dto);
            ProductResponseDto response = ProductResponseDto.fromDomain(product);
            return ResponseEntity.ok(ApiResponseWrapper.ok(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseWrapper.error("Produit introuvable", "NOT_FOUND", "PRODUCT_NOT_FOUND", null));
        }
    }

    /**
     * Archive product (soft delete)
     * PATCH /api/v1/products/{id}/archive
     */
    @PatchMapping("/{id}/archive")
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> archiveProduct(@PathVariable UUID id) {
        // Extract actorId from SecurityContext — set by JwtAuthFilter
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        
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
    @GetMapping("/{productId}/supplier")
    public ResponseEntity<ApiResponseWrapper<SupplierResponseDto>> getProductSupplier(
            @PathVariable UUID productId) {
        return getSupplierByProductUseCase.execute(productId)
                .map(supplier -> {
                    var profile = getSupplierProfileUseCase.execute(supplier.id());
                    return ResponseEntity.ok(
                            ApiResponseWrapper.ok(SupplierResponseDto.fromDomain(supplier, profile.productIds())));
                })
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
}