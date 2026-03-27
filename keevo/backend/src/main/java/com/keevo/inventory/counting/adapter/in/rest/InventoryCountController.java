package com.keevo.inventory.counting.adapter.in.rest;

import com.keevo.inventory.counting.adapter.in.rest.dto.*;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;
import com.keevo.inventory.counting.domain.model.QuickAddProductResult;
import com.keevo.inventory.counting.domain.port.in.*;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory/sessions/{sessionId}")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
public class InventoryCountController {

    private final GetCountingProductsUseCase getCountingProductsUseCase;
    private final SaveInventoryCountUseCase saveInventoryCountUseCase;
    private final GetSessionCountsUseCase getSessionCountsUseCase;
    private final QuickAddProductUseCase quickAddProductUseCase;

    public InventoryCountController(GetCountingProductsUseCase getCountingProductsUseCase,
                                    SaveInventoryCountUseCase saveInventoryCountUseCase,
                                    GetSessionCountsUseCase getSessionCountsUseCase,
                                    QuickAddProductUseCase quickAddProductUseCase) {
        this.getCountingProductsUseCase = getCountingProductsUseCase;
        this.saveInventoryCountUseCase = saveInventoryCountUseCase;
        this.getSessionCountsUseCase = getSessionCountsUseCase;
        this.quickAddProductUseCase = quickAddProductUseCase;
    }

    @Operation(summary = "List products in scope with counting status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Products listed"),
            @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @GetMapping("/products")
    public ResponseEntity<ApiResponseWrapper<List<InventoryProductRowResponseDto>>> getProducts(
            @PathVariable UUID sessionId) {

        List<InventoryProductRow> rows = getCountingProductsUseCase.execute(
                new GetCountingProductsQuery(sessionId));

        List<InventoryProductRowResponseDto> dtos = rows.stream()
                .map(InventoryProductRowResponseDto::fromDomain)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Save or update a single physical count")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Count saved"),
            @ApiResponse(responseCode = "404", description = "Session not found"),
            @ApiResponse(responseCode = "409", description = "Session not in progress")
    })
    @PostMapping("/counts")
    public ResponseEntity<ApiResponseWrapper<InventoryCountResponseDto>> saveCount(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SaveInventoryCountRequestDto request) {

        UUID actorId = extractActorId();
        var command = new SaveInventoryCountCommand(
                sessionId, request.productId(), request.variantId(),
                request.productName(), request.variantLabel(),
                request.theoretical(), request.physical(), actorId);

        InventoryCount saved = saveInventoryCountUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(InventoryCountResponseDto.fromDomain(saved)));
    }

    @Operation(summary = "Bulk upsert counts (sync push)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Counts saved"),
            @ApiResponse(responseCode = "409", description = "Session not in progress")
    })
    @PutMapping("/counts")
    public ResponseEntity<ApiResponseWrapper<List<InventoryCountResponseDto>>> bulkSaveCounts(
            @PathVariable UUID sessionId,
            @Valid @RequestBody BulkSaveCountsRequestDto request) {

        UUID actorId = extractActorId();
        List<InventoryCountResponseDto> results = new ArrayList<>();

        for (SaveInventoryCountRequestDto item : request.counts()) {
            var command = new SaveInventoryCountCommand(
                    sessionId, item.productId(), item.variantId(),
                    item.productName(), item.variantLabel(),
                    item.theoretical(), item.physical(), actorId);
            InventoryCount saved = saveInventoryCountUseCase.execute(command);
            results.add(InventoryCountResponseDto.fromDomain(saved));
        }

        return ResponseEntity.ok(ApiResponseWrapper.ok(results));
    }

    @Operation(summary = "Get all counts for a session")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Counts listed")
    })
    @GetMapping("/counts")
    public ResponseEntity<ApiResponseWrapper<List<InventoryCountResponseDto>>> getCounts(
            @PathVariable UUID sessionId) {

        List<InventoryCount> counts = getSessionCountsUseCase.execute(
                new GetSessionCountsQuery(sessionId));

        List<InventoryCountResponseDto> dtos = counts.stream()
                .map(InventoryCountResponseDto::fromDomain)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Quick-add a product during inventory counting")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Product created with count"),
            @ApiResponse(responseCode = "404", description = "Session not found"),
            @ApiResponse(responseCode = "409", description = "Product name already exists or session not in progress")
    })
    @PostMapping("/quick-add")
    public ResponseEntity<ApiResponseWrapper<QuickAddProductResponseDto>> quickAddProduct(
            @PathVariable UUID sessionId,
            @Valid @RequestBody QuickAddProductRequestDto request) {

        UUID actorId = extractActorId();
        var command = new QuickAddProductCommand(
                sessionId, request.name(), request.categoryId(),
                request.physicalQty(), request.sellingPrice(), actorId
        );

        QuickAddProductResult result = quickAddProductUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(QuickAddProductResponseDto.fromDomain(result)));
    }

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
