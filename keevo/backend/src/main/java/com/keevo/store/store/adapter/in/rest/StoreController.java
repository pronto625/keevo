package com.keevo.store.store.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.store.store.adapter.in.rest.dto.CreateStoreRequestDto;
import com.keevo.store.store.adapter.in.rest.dto.StoreResponseDto;
import com.keevo.store.store.adapter.in.rest.dto.UpdateStoreRequestDto;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * StoreController — REST adapter for store management endpoints.
 *
 * <p>All endpoints require JWT authentication (protected by default JwtAuthFilter).
 * ActorId is extracted from SecurityContextHolder, set by JwtAuthFilter.
 *
 * <p>Story 3.1 — AC1–AC6.
 */
@Tag(name = "Stores", description = "Store & Warehouse management")
@RestController
@RequestMapping("/api/v1/stores")
@PreAuthorize("hasRole('OWNER')")
public class StoreController {

    private final CreateStoreUseCase createStoreUseCase;
    private final UpdateStoreUseCase updateStoreUseCase;
    private final DeactivateStoreUseCase deactivateStoreUseCase;
    private final ListStoresUseCase listStoresUseCase;

    public StoreController(
            CreateStoreUseCase createStoreUseCase,
            UpdateStoreUseCase updateStoreUseCase,
            DeactivateStoreUseCase deactivateStoreUseCase,
            ListStoresUseCase listStoresUseCase) {
        this.createStoreUseCase = createStoreUseCase;
        this.updateStoreUseCase = updateStoreUseCase;
        this.deactivateStoreUseCase = deactivateStoreUseCase;
        this.listStoresUseCase = listStoresUseCase;
    }

    @Operation(summary = "Create a new store or warehouse")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Store created"),
            @ApiResponse(responseCode = "403", description = "Plan limit exceeded"),
            @ApiResponse(responseCode = "409", description = "Warehouse already exists")
    })
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<StoreResponseDto>> createStore(
            @Valid @RequestBody CreateStoreRequestDto request) {
        requireOwner();
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        CreateStoreCommand command = new CreateStoreCommand(
                request.name(), request.type(), request.address(), request.phone(), actorId);
        Store store = createStoreUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(StoreResponseDto.fromDomain(store)));
    }

    @Operation(summary = "List stores")
    @ApiResponse(responseCode = "200", description = "Store list returned")
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<StoreResponseDto>>> listStores(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        requireOwner();
        List<StoreResponseDto> dtos = listStoresUseCase.execute(new ListStoresQuery(includeInactive))
                .stream().map(StoreResponseDto::fromDomain).toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Update a store's name, address or phone")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Store updated"),
            @ApiResponse(responseCode = "404", description = "Store not found")
    })
    @PatchMapping("/{storeId}")
    public ResponseEntity<ApiResponseWrapper<StoreResponseDto>> updateStore(
            @PathVariable UUID storeId,
            @Valid @RequestBody UpdateStoreRequestDto request) {
        requireOwner();
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        UpdateStoreCommand command = new UpdateStoreCommand(
                storeId, request.name(), request.address(), request.phone(), actorId);
        Store store = updateStoreUseCase.execute(command);
        return ResponseEntity.ok(ApiResponseWrapper.ok(StoreResponseDto.fromDomain(store)));
    }

    @Operation(summary = "Deactivate a store (soft-delete)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Store deactivated"),
            @ApiResponse(responseCode = "404", description = "Store not found")
    })
    @PatchMapping("/{storeId}/deactivate")
    public ResponseEntity<ApiResponseWrapper<StoreResponseDto>> deactivateStore(
            @PathVariable UUID storeId) {
        requireOwner();
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Store store = deactivateStoreUseCase.execute(new DeactivateStoreCommand(storeId, actorId));
        return ResponseEntity.ok(ApiResponseWrapper.ok(StoreResponseDto.fromDomain(store)));
    }

    // ── Defense-in-depth guard ───────────────────────────────────────────

    private void requireOwner() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isOwner = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
        if (!isOwner) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can manage stores");
        }
    }
}
