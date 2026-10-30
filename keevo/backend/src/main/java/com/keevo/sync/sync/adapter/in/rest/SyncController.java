package com.keevo.sync.sync.adapter.in.rest;

import com.keevo.shared.application.port.AuditContext;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncOperationDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPullResponseDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushRequestDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushResponseDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncConflictDto;
import com.keevo.sync.sync.application.service.SyncGateCheckService;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncPullResult;
import com.keevo.sync.sync.domain.model.UserSyncState;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SyncController — REST adapter for sync operations.
 */
@RestController
@RequestMapping("/api/v1/sync")
@Tag(name = "Sync", description = "Offline-first synchronisation — push/pull operations")
public class SyncController {

    /** Story 12.12 — dedicated security logger (route to the security/audit appender). */
    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");
    private static final String PUSH_ENDPOINT = "/api/v1/sync/push";
    private static final String PULL_ENDPOINT = "/api/v1/sync/pull";

    private final SyncUseCase syncUseCase;
    private final JwtTokenProvider jwtTokenProvider;
    private final SyncConflictsLogRepository conflictsLogRepository;
    private final SyncGateCheckService syncGateCheckService;
    private final UserSyncStateRepository userSyncStateRepository;

    public SyncController(SyncUseCase syncUseCase, JwtTokenProvider jwtTokenProvider,
                          SyncConflictsLogRepository conflictsLogRepository,
                          SyncGateCheckService syncGateCheckService,
                          UserSyncStateRepository userSyncStateRepository) {
        this.syncUseCase = syncUseCase;
        this.jwtTokenProvider = jwtTokenProvider;
        this.conflictsLogRepository = conflictsLogRepository;
        this.syncGateCheckService = syncGateCheckService;
        this.userSyncStateRepository = userSyncStateRepository;
    }

    /**
     * Verify that the deviceId belongs to the authenticated actor.
     * Throws DEVICE_ID_MISMATCH if the device exists and is owned by a different user.
     * Tolerates an unknown device (Optional.empty).
     *
     * <p>Story 12.12: every mismatch emits a WARN on the {@code SECURITY} logger (actor, owner,
     * tenant, device, endpoint) so probing is visible. Device ids are client UUIDv4 (122 random
     * bits): the distinct 403 is kept on purpose — enumeration is infeasible and a generic
     * error would hide a real misconfiguration from the legitimate owner.
     */
    private void verifyDeviceOwnership(Optional<UserSyncState> syncStateOpt, String deviceId,
                                       UUID actorId, String tenantId, String endpoint) {
        syncStateOpt.ifPresent(s -> {
            if (s.userId() != null && !s.userId().equals(actorId)) {
                securityLog.warn("DEVICE_ID_MISMATCH actorId={} ownerId={} tenantId={} deviceId={} endpoint={}",
                        actorId, s.userId(), tenantId, sanitizeForLog(deviceId), endpoint);
                throw new DomainException(
                        ErrorCode.DEVICE_ID_MISMATCH,
                        "Ce device n'est pas associé à votre compte",
                        Map.of("deviceId", deviceId));
            }
        });
    }

    /** Story 12.12 — guarded upsert wrote nothing: the device was claimed by another user meanwhile. */
    private void warnIfNotWritten(boolean written, String deviceId, UUID actorId, String tenantId,
                                  String endpoint) {
        if (!written) {
            securityLog.warn("DEVICE_OWNERSHIP_RACE upsert skipped (device owned by another user) "
                    + "actorId={} tenantId={} deviceId={} endpoint={}",
                    actorId, tenantId, sanitizeForLog(deviceId), endpoint);
        }
    }

    /**
     * Story 12.12 — the deviceId is client-supplied (the pull header is unvalidated): strip control
     * characters (CR/LF included, no forged log lines) and cap the length before logging it.
     */
    static String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) + "…" : cleaned;
    }

    @EntitlementExempt("synchronisation (contrôle par opération)")
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
        String deviceId = request.deviceId();

        // ── DeviceId ownership check + gate check (single DB query) ────────────
        // v1s-13-2: ownership verification (B-CRIT-3 rotation bypass)
        // v5.4: 7-day offline gate (B-CRIT-3 staleness)
        Optional<UserSyncState> syncStateOpt = userSyncStateRepository.findByDeviceId(deviceId);
        verifyDeviceOwnership(syncStateOpt, deviceId, actorId, tenantId, PUSH_ENDPOINT);

        // ── 7-day offline gate check ──────────────────────────────────────────
        if (syncGateCheckService.isStalePush(deviceId)) {
            Instant lastPushAt = syncStateOpt.map(UserSyncState::lastPushAt).orElse(null);
            long daysSince = lastPushAt != null
                    ? ChronoUnit.DAYS.between(lastPushAt, Instant.now())
                    : 0L;
            return ResponseEntity.status(HttpStatus.LOCKED).body(
                    ApiResponseWrapper.error(
                            "Synchronisation requise — données trop anciennes",
                            "SYNC_REQUIRED",
                            "SYNC_REQUIRED",
                            Map.of("daysSinceLastSync", daysSince,
                                    "lastPushAt", lastPushAt != null ? lastPushAt.toString() : "")));
        }

        var operations = request.operations().stream()
                .map(dto -> new SyncOperation(
                        dto.operationId(), dto.operationType(), dto.entityId(),
                        dto.payload(), dto.clientTimestamp()))
                .toList();

        // Story v1s-18-6 — audit entries written while applying the batch carry source=SYNC and
        // this deviceId (listener runs synchronously on this thread; context restored in finally).
        SyncBatchResult result = AuditContext.runAsSync(deviceId, () -> syncUseCase.pushBatch(
                new PushBatchCommand(actorId, tenantId, deviceId, operations)));

        // ── Record successful push timestamp ──────────────────────────────────
        boolean written = userSyncStateRepository.upsert(
                new UserSyncState(deviceId, actorId, tenantId, Instant.now(), null, Instant.now()));
        warnIfNotWritten(written, deviceId, actorId, tenantId, PUSH_ENDPOINT);

        return ResponseEntity.ok(ApiResponseWrapper.ok(SyncPushResponseDto.from(result)));
    }

    @EntitlementExempt("synchronisation (contrôle par opération)")
    @GetMapping("/pull")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @Operation(summary = "Pull delta changes since timestamp",
            description = "Returns all entities modified after the given timestamp. " +
                    "Omit 'since' for full sync (first-time). " +
                    "Response includes serverTimestamp as the cursor for next pull. " +
                    "When hasMore is true, call again with cursor=nextCursor and the same 'since' " +
                    "before saving serverTimestamp.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delta entities returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<ApiResponseWrapper<SyncPullResponseDto>> pull(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest httpRequest) {

        UUID actorId = extractActorId();
        Claims claims = extractClaims(httpRequest);
        String tenantId = claims.get("tenantId", String.class);

        // Register device on pull so it appears in active devices list
        if (deviceId != null && !deviceId.isBlank()) {
            // ── DeviceId ownership check (v1s-13-2 — B-CRIT-3 rotation bypass) ─────
            verifyDeviceOwnership(userSyncStateRepository.findByDeviceId(deviceId),
                    deviceId, actorId, tenantId, PULL_ENDPOINT);

            boolean written = userSyncStateRepository.upsertOnPull(
                    new UserSyncState(deviceId, actorId, tenantId, null, Instant.now(), Instant.now()));
            warnIfNotWritten(written, deviceId, actorId, tenantId, PULL_ENDPOINT);
        }

        Instant sinceInstant = (since != null && !since.isBlank())
                ? Instant.parse(since)
                : null;

        SyncPullResult result = syncUseCase.pull(
                new SyncUseCase.PullCommand(actorId, tenantId, sinceInstant,
                        cursor != null && !cursor.isBlank() ? cursor : null,
                        CurrentActor.isOwner()));

        return ResponseEntity.ok(ApiResponseWrapper.ok(SyncPullResponseDto.from(result)));
    }

    @RequiresEntitlement(value = "sync", access = RequiresEntitlement.Access.READ)
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

    @RequiresEntitlement(value = "sync", access = RequiresEntitlement.Access.READ)
    @GetMapping("/devices")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @Operation(summary = "List active devices for current tenant",
            description = "Returns all devices that have pushed sync data for this tenant.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Device list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<ApiResponseWrapper<List<Map<String, Object>>>> getDevices(
            HttpServletRequest httpRequest) {
        Claims claims = extractClaims(httpRequest);
        String tenantId = claims.get("tenantId", String.class);
        // J24 (device test): the device id is renewed on every account switch (A29), so
        // the raw list counted 18 « appareils actifs » for one phone. Only the devices
        // seen in the last ACTIVE_DEVICE_WINDOW are listed (and labelled so in the app).
        Instant since = Instant.now().minus(ACTIVE_DEVICE_WINDOW);
        List<UserSyncState> devices = userSyncStateRepository.findAllByTenantId(tenantId).stream()
                .filter(d -> lastSeen(d).isAfter(since))
                .toList();
        List<Map<String, Object>> dtos = devices.stream().map(d -> Map.<String, Object>of(
                "deviceId", d.deviceId(),
                "userId", d.userId().toString(),
                "lastPushAt", d.lastPushAt() != null ? d.lastPushAt().toString() : "",
                "updatedAt", d.updatedAt().toString()
        )).toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    /** J24: a device counts as active if seen (push, pull or update) within this window. */
    static final java.time.Duration ACTIVE_DEVICE_WINDOW = java.time.Duration.ofDays(30);

    private static Instant lastSeen(UserSyncState d) {
        Instant seen = d.updatedAt() != null ? d.updatedAt() : Instant.EPOCH;
        if (d.lastPushAt() != null && d.lastPushAt().isAfter(seen)) seen = d.lastPushAt();
        if (d.lastPullAt() != null && d.lastPullAt().isAfter(seen)) seen = d.lastPullAt();
        return seen;
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
