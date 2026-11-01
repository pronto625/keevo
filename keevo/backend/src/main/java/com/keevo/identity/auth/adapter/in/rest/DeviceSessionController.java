package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.adapter.in.rest.dto.DeviceSessionDto;
import com.keevo.identity.auth.application.service.DeviceSessionService;
import com.keevo.identity.auth.domain.model.DeviceSessionView;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 16.11 — « Appareils connectés »: my device sessions, and (owner) an employee's.
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/sessions} — my open sessions in this account (current flag)</li>
 *   <li>{@code DELETE /api/v1/auth/sessions/{sessionId}} — close one of mine</li>
 *   <li>{@code DELETE /api/v1/auth/sessions} — close all my other sessions</li>
 *   <li>{@code GET /api/v1/employees/{employeeId}/sessions} — owner: an employee's sessions</li>
 *   <li>{@code DELETE /api/v1/employees/{employeeId}/sessions[/{sessionId}]} — owner: close one / all</li>
 * </ul>
 * Closing a session makes that device's next request answer 401 {@code SESSION_REVOKED}.
 */
@Tag(name = "Device sessions", description = "Appareils connectés")
@EntitlementExempt("authentification: appareils connectés")
@RestController
public class DeviceSessionController {

    static final String UNKNOWN_DEVICE = "Appareil inconnu";

    private final DeviceSessionService sessions;

    public DeviceSessionController(DeviceSessionService sessions) {
        this.sessions = sessions;
    }

    @Operation(summary = "List my connected devices")
    @GetMapping("/api/v1/auth/sessions")
    public ResponseEntity<ApiResponseWrapper<List<DeviceSessionDto>>> mySessions() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(toDtos(
                sessions.list(CurrentActor.actorId(), tenant(), CurrentActor.sessionIdOrNull()))));
    }

    @Operation(summary = "Close one of my connected devices")
    @DeleteMapping("/api/v1/auth/sessions/{sessionId}")
    public ResponseEntity<ApiResponseWrapper<Void>> closeMine(@PathVariable UUID sessionId) {
        UUID me = CurrentActor.actorId();
        sessions.close(me, me, tenant(), sessionId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Close all my other connected devices")
    @DeleteMapping("/api/v1/auth/sessions")
    public ResponseEntity<ApiResponseWrapper<Map<String, Integer>>> closeMyOthers() {
        UUID me = CurrentActor.actorId();
        int closed = sessions.closeOthers(me, me, tenant(), CurrentActor.sessionIdOrNull(), true);
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("closed", closed)));
    }

    @Operation(summary = "Owner: list an employee's connected devices")
    @GetMapping("/api/v1/employees/{employeeId}/sessions")
    public ResponseEntity<ApiResponseWrapper<List<DeviceSessionDto>>> employeeSessions(
            @PathVariable UUID employeeId) {
        CurrentActor.requireOwner("OWNER role required");
        UUID userId = sessions.employeeUserId(employeeId);
        UUID current = userId.equals(CurrentActor.actorId()) ? CurrentActor.sessionIdOrNull() : null;
        return ResponseEntity.ok(ApiResponseWrapper.ok(toDtos(sessions.list(userId, tenant(), current))));
    }

    @Operation(summary = "Owner: close one of an employee's connected devices")
    @DeleteMapping("/api/v1/employees/{employeeId}/sessions/{sessionId}")
    public ResponseEntity<ApiResponseWrapper<Void>> closeEmployeeSession(@PathVariable UUID employeeId,
                                                                        @PathVariable UUID sessionId) {
        CurrentActor.requireOwner("OWNER role required");
        UUID userId = sessions.employeeUserId(employeeId);
        sessions.close(CurrentActor.actorId(), userId, tenant(), sessionId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Owner: close all of an employee's connected devices")
    @DeleteMapping("/api/v1/employees/{employeeId}/sessions")
    public ResponseEntity<ApiResponseWrapper<Map<String, Integer>>> closeEmployeeSessions(
            @PathVariable UUID employeeId) {
        CurrentActor.requireOwner("OWNER role required");
        UUID userId = sessions.employeeUserId(employeeId);
        UUID keep = userId.equals(CurrentActor.actorId()) ? CurrentActor.sessionIdOrNull() : null;
        int closed = sessions.closeOthers(CurrentActor.actorId(), userId, tenant(), keep, false);
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("closed", closed)));
    }

    private static String tenant() {
        return TenantContext.getCurrentTenant();
    }

    private static List<DeviceSessionDto> toDtos(List<DeviceSessionView> views) {
        return views.stream()
                .map(v -> new DeviceSessionDto(v.sessionId(),
                        v.deviceLabel() != null ? v.deviceLabel() : UNKNOWN_DEVICE,
                        v.lastUsedAt(), v.current()))
                .toList();
    }
}
