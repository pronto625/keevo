package com.keevo.sync.sync.adapter.in.rest;

import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncOperationDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushRequestDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushResponseDto;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand;
import io.jsonwebtoken.Claims;
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

import java.util.UUID;

/**
 * SyncController — REST adapter for sync operations.
 */
@RestController
@RequestMapping("/api/v1/sync")
@Tag(name = "Sync", description = "Offline-first synchronisation — push/pull operations")
public class SyncController {

    private final SyncUseCase syncUseCase;
    private final JwtTokenProvider jwtTokenProvider;

    public SyncController(SyncUseCase syncUseCase, JwtTokenProvider jwtTokenProvider) {
        this.syncUseCase = syncUseCase;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/push")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @Operation(summary = "Push batch of offline operations", description = "Processes a batch of queued offline operations. Each operation is handled independently — partial success is possible.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch processed (check per-operation status in results)"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<ApiResponseWrapper<SyncPushResponseDto>> push(
            @Valid @RequestBody SyncPushRequestDto request,
            HttpServletRequest httpRequest) {

        UUID actorId = extractActorId();
        Claims claims = extractClaims(httpRequest);
        String tenantId = claims.get("tenantId", String.class);

        var operations = request.operations().stream()
                .map(dto -> new SyncOperation(
                        dto.operationId(), dto.operationType(), dto.entityId(),
                        dto.payload(), dto.clientTimestamp()))
                .toList();

        SyncBatchResult result = syncUseCase.pushBatch(
                new PushBatchCommand(actorId, tenantId, request.deviceId(), operations));

        return ResponseEntity.ok(ApiResponseWrapper.ok(SyncPushResponseDto.from(result)));
    }

    /**
     * GET /api/v1/sync/pull — STUB until Story 5.2.
     */
    @GetMapping("/pull")
    @Operation(summary = "Pull delta changes (STUB)", description = "Not implemented — Story 5.2")
    @ApiResponse(responseCode = "501", description = "Not implemented")
    public ResponseEntity<Void> pull(@RequestParam(required = false) String since) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private Claims extractClaims(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        return jwtTokenProvider.parseToken(token);
    }
}
