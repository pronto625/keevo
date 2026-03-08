package com.keevo.catalog.product.adapter.in.web;

import com.keevo.catalog.product.adapter.in.web.dto.CreateProductRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.UpdateProductRequestDto;
import com.keevo.catalog.product.adapter.in.web.dto.ProductResponseDto;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
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
@PreAuthorize("hasRole('USER')")
public class ProductController {

    private final CreateProductUseCase createProductUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final ArchiveProductUseCase archiveProductUseCase;
    private final ProductRepository productRepository;

    public ProductController(CreateProductUseCase createProductUseCase,
                           UpdateProductUseCase updateProductUseCase,
                           ArchiveProductUseCase archiveProductUseCase,
                           ProductRepository productRepository) {
        this.createProductUseCase = createProductUseCase;
        this.updateProductUseCase = updateProductUseCase;
        this.archiveProductUseCase = archiveProductUseCase;
        this.productRepository = productRepository;
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
                actorId
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
                actorId
        );
        
        Product product = updateProductUseCase.execute(dto);
        ProductResponseDto response = ProductResponseDto.fromDomain(product);
        
        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * Archive product (soft delete)
     * PATCH /api/v1/products/{id}/archive
     */
    @PatchMapping("/{id}/archive")
    public ResponseEntity<Void> archiveProduct(@PathVariable UUID id) {
        // Extract actorId from SecurityContext — set by JwtAuthFilter
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        
        var dto = new ArchiveProductUseCase.ArchiveProductDto(id, actorId);
        archiveProductUseCase.execute(dto);
        return ResponseEntity.noContent().build();
    }
}