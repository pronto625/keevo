package com.keevo.sync.sync.adapter.in.rest;

import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncOperationDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPullResponseDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushRequestDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushResponseDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncConflictDto;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncPullResult;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
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
    private final SyncConflictsLogRepository conflictsLogRepository;

    public SyncController(SyncUseCase syncUseCase, JwtTokenProvider jwtTokenProvider,
                           SyncConflictsLogRepository conflictsLogRepository) {
        this.syncUseCase = syncUseCase;
        this.jwtTokenProvider = jwtTokenProvider;
        this.conflictsLogRepository = conflictsLogRepository;
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

    @GetMapping("/pull")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @Operation(summary = "Pull delta changes since timestamp",
            description = "Returns all entities modified after the given timestamp. " +
                    "Omit 'since' for full sync (first-time). " +
                    "Response includes serverTimestamp as the cursor for next pull.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delta entities returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<ApiResponseWrapper<SyncPullResponseDto>> pull(
            @RequestParam(required = false) String since,
            HttpServletRequest httpRequest) {

        UUID actorId = extractActorId();
        Claims claims = extractClaims(httpRequest);
        String tenantId = claims.get("tenantId", String.class);

        Instant sinceInstant = (since != null && !since.isBlank())
                ? Instant.parse(since)
                : null;

        SyncPullResult result = syncUseCase.pull(
                new SyncUseCase.PullCommand(actorId, tenantId, sinceInstant));

        return ResponseEntity.ok(ApiResponseWrapper.ok(SyncPullResponseDto.from(result)));
    }

    @GetMapping("/conflicts")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "List sync conflict log",
            description = "Returns paginated list of sync conflicts for OWNER review.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conflict log returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions — OWNER only")
    })
    public ResponseEntity<ApiResponseWrapper<List<SyncConflictDto>>> getConflicts(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<SyncConflictDto> conflicts = conflictsLogRepository.findAll(limit, offset)
                .stream()
                .map(SyncConflictDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(conflicts));
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
