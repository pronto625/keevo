package com.keevo.inventory.counting.adapter.in.rest;

import com.keevo.inventory.counting.adapter.in.rest.dto.CreateInventorySessionRequestDto;
import com.keevo.inventory.counting.adapter.in.rest.dto.InventorySessionResponseDto;
import com.keevo.inventory.counting.adapter.in.rest.dto.ValidateInventoryResponseDto;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Inventory", description = "Inventory session management")
@RestController
@RequestMapping("/api/v1/inventory/sessions")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
public class InventorySessionController {

    private final CreateInventorySessionUseCase createSessionUseCase;
    private final GetActiveSessionUseCase getActiveSessionUseCase;
    private final CancelInventorySessionUseCase cancelSessionUseCase;
    private final ListInventorySessionsUseCase listSessionsUseCase;
    private final ValidateInventoryUseCase validateInventoryUseCase;
    private final JwtTokenProvider jwtTokenProvider;

    public InventorySessionController(CreateInventorySessionUseCase createSessionUseCase,
                                       GetActiveSessionUseCase getActiveSessionUseCase,
                                       CancelInventorySessionUseCase cancelSessionUseCase,
                                       ListInventorySessionsUseCase listSessionsUseCase,
                                       ValidateInventoryUseCase validateInventoryUseCase,
                                       JwtTokenProvider jwtTokenProvider) {
        this.createSessionUseCase = createSessionUseCase;
        this.getActiveSessionUseCase = getActiveSessionUseCase;
        this.cancelSessionUseCase = cancelSessionUseCase;
        this.listSessionsUseCase = listSessionsUseCase;
        this.validateInventoryUseCase = validateInventoryUseCase;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Operation(summary = "Start a new inventory session")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Session created"),
            @ApiResponse(responseCode = "409", description = "Active session already exists for store"),
            @ApiResponse(responseCode = "404", description = "Store not found")
    })
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<InventorySessionResponseDto>> createSession(
            @Valid @RequestBody CreateInventorySessionRequestDto request) {

        UUID actorId = extractActorId();
        var command = new CreateInventorySessionCommand(
                request.storeId(), request.scope(), request.categoryIds(), actorId);

        InventorySession session = createSessionUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(InventorySessionResponseDto.fromDomain(session)));
    }

    @Operation(summary = "Get the active inventory session for a store")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active session found"),
            @ApiResponse(responseCode = "404", description = "No active session")
    })
    @GetMapping("/active")
    public ResponseEntity<ApiResponseWrapper<InventorySessionResponseDto>> getActiveSession(
            @RequestParam UUID storeId) {

        return getActiveSessionUseCase.execute(new GetActiveSessionQuery(storeId))
                .map(session -> ResponseEntity.ok(
                        ApiResponseWrapper.ok(InventorySessionResponseDto.fromDomain(session))))
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "No active session for store: " + storeId));
    }

    @Operation(summary = "Cancel an inventory session")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session cancelled"),
            @ApiResponse(responseCode = "403", description = "Employee cannot cancel another user's session"),
            @ApiResponse(responseCode = "404", description = "Session not found"),
            @ApiResponse(responseCode = "409", description = "Session is not in progress")
    })
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponseWrapper<InventorySessionResponseDto>> cancelSession(
            @PathVariable UUID id, HttpServletRequest httpRequest) {

        UUID actorId = extractActorId();
        String role = extractRole(httpRequest);

        InventorySession result = cancelSessionUseCase.execute(
                new CancelInventorySessionCommand(id, actorId, role));

        return ResponseEntity.ok(
                ApiResponseWrapper.ok(InventorySessionResponseDto.fromDomain(result)));
    }

    @Operation(summary = "List inventory sessions (paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sessions listed")
    })
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<InventorySessionResponseDto>>> listSessions(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var sessions = listSessionsUseCase.execute(
                new ListInventorySessionsQuery(storeId, page, size));
        var dtos = sessions.stream()
                .map(InventorySessionResponseDto::fromDomain)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    // ── Private helpers ───────────────────────────────────────────

    @Operation(summary = "Validate inventory session and apply stock adjustments")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Inventory validated, adjustments applied"),
            @ApiResponse(responseCode = "404", description = "Session not found"),
            @ApiResponse(responseCode = "409", description = "Session not in IN_PROGRESS status"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @PostMapping("/{id}/validate")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<ValidateInventoryResponseDto>> validateInventory(
            @PathVariable UUID id) {
        // Defense-in-depth: explicit role check (testable with standaloneSetup)
        boolean isOwner = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"));
        if (!isOwner) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can validate inventory");
        }
        UUID actorId = extractActorId();
        var result = validateInventoryUseCase.execute(
                new ValidateInventoryCommand(id, actorId));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                ValidateInventoryResponseDto.fromDomain(result)));
    }

    // ── Private helper methods ───────────────────────────────────────────

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private String extractRole(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return "EMPLOYEE";
        }
        String token = authHeader.substring(7);
        return jwtTokenProvider.extractRole(jwtTokenProvider.parseToken(token));
    }
}
