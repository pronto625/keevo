package com.keevo.catalog.contact.adapter.in.web;

import com.keevo.catalog.contact.adapter.in.web.dto.*;
import com.keevo.catalog.contact.application.usecase.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * SupplierController — REST adapter for supplier management endpoints (Story 2.5).
 *
 * <p>All endpoints require an authenticated user (JWT set by JwtAuthFilter).
 */
@RestController
@RequestMapping("/api/v1/suppliers")
@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
public class SupplierController {

    private final CreateSupplierUseCase createSupplierUseCase;
    private final GetSuppliersUseCase getSuppliersUseCase;
    private final GetSupplierProfileUseCase getSupplierProfileUseCase;
    private final UpdateSupplierUseCase updateSupplierUseCase;
    private final ArchiveSupplierUseCase archiveSupplierUseCase;

    public SupplierController(CreateSupplierUseCase createSupplierUseCase,
                              GetSuppliersUseCase getSuppliersUseCase,
                              GetSupplierProfileUseCase getSupplierProfileUseCase,
                              UpdateSupplierUseCase updateSupplierUseCase,
                              ArchiveSupplierUseCase archiveSupplierUseCase) {
        this.createSupplierUseCase   = createSupplierUseCase;
        this.getSuppliersUseCase     = getSuppliersUseCase;
        this.getSupplierProfileUseCase = getSupplierProfileUseCase;
        this.updateSupplierUseCase   = updateSupplierUseCase;
        this.archiveSupplierUseCase  = archiveSupplierUseCase;
    }

    /** POST /api/v1/suppliers */
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<SupplierResponseDto>> createSupplier(
            @Valid @RequestBody CreateSupplierRequestDto request) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var cmd = new CreateSupplierUseCase.CreateSupplierCommand(
                request.name(), request.phone(), request.email(),
                request.productIds() != null ? request.productIds() : List.of(),
                actorId, TenantContext.getCurrentTenant());
        var supplier = createSupplierUseCase.execute(cmd);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(SupplierResponseDto.fromDomain(supplier)));
    }

    /** GET /api/v1/suppliers?search=&includeArchived= */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<SupplierResponseDto>>> listSuppliers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        var suppliers = getSuppliersUseCase.execute(
                new GetSuppliersUseCase.GetSuppliersQuery(search, includeArchived));
        var dtos = suppliers.stream()
                .map(s -> SupplierResponseDto.fromDomain(s,
                        getSupplierProfileUseCase.execute(s.id()).productIds()))
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    /** GET /api/v1/suppliers/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<SupplierProfileResponseDto>> getSupplierProfile(
            @PathVariable UUID id) {
        try {
            var result = getSupplierProfileUseCase.execute(id);
            return ResponseEntity.ok(ApiResponseWrapper.ok(
                    SupplierProfileResponseDto.fromResult(result)));
        } catch (DomainException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseWrapper.error("Fournisseur introuvable", "NOT_FOUND",
                            "SUPPLIER_NOT_FOUND", null));
        }
    }

    /** PATCH /api/v1/suppliers/{id} */
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<SupplierResponseDto>> updateSupplier(
            @PathVariable UUID id,
            @RequestBody UpdateSupplierRequestDto request) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var cmd = new UpdateSupplierUseCase.UpdateSupplierCommand(
                id, request.name(), request.phone(), request.email(), request.productIds(), actorId);
        var supplier = updateSupplierUseCase.execute(cmd);
        var productIds = getSupplierProfileUseCase.execute(id).productIds();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                SupplierResponseDto.fromDomain(supplier, productIds)));
    }

    /** DELETE /api/v1/suppliers/{id} (soft delete) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archiveSupplier(@PathVariable UUID id) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        archiveSupplierUseCase.execute(id, actorId, TenantContext.getCurrentTenant());
        return ResponseEntity.noContent().build();
    }
}
