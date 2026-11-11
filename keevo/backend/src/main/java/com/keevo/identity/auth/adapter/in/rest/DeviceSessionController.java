package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.adapter.in.rest.dto.DeviceSessionDto;
import com.keevo.identity.auth.application.service.DeviceSessionService;
import com.keevo.identity.auth.domain.model.DeviceSessionView;
import com.keevo.identity.employee.adapter.in.rest.EmployeeTeamScope;
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
 * Story 16.11 — « Appareils connectés »: my device sessions, and (owner) an employee's. Story 19.5b — a member's
 * sessions follow the team rule ({@link EmployeeTeamScope}): the owner for every member, a delegated team manager
 * ({@code TEAM_MANAGE}, MANAGER by default) for the EMPLOYEE / VIEWER members of his store; anyone else → 403.
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/sessions} — my open sessions in this account (current flag)</li>
 *   <li>{@code DELETE /api/v1/auth/sessions/{sessionId}} — close one of mine</li>
 *   <li>{@code DELETE /api/v1/auth/sessions} — close all my other sessions</li>
 *   <li>{@code GET /api/v1/employees/{employeeId}/sessions} — team rule: a member's sessions</li>
 *   <li>{@code DELETE /api/v1/employees/{employeeId}/sessions[/{sessionId}]} — team rule: close one / all</li>
 * </ul>
 * Closing a session makes that device's next request answer 401 {@code SESSION_REVOKED}.
 */
@Tag(name = "Device sessions", description = "Appareils connectés")
@EntitlementExempt("authentification: appareils connectés")
@RestController
public class DeviceSessionController {

    static final String UNKNOWN_DEVICE = "Appareil inconnu";

    private final DeviceSessionService sessions;
    private final EmployeeTeamScope teamScope; // Story 19.5b

    public DeviceSessionController(DeviceSessionService sessions, EmployeeTeamScope teamScope) {
        this.sessions = sessions;
        this.teamScope = teamScope;
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

    @Operation(summary = "Owner or store manager: list an employee's connected devices")
    @GetMapping("/api/v1/employees/{employeeId}/sessions")
    public ResponseEntity<ApiResponseWrapper<List<DeviceSessionDto>>> employeeSessions(
            @PathVariable UUID employeeId) {
        UUID userId = userOf(employeeId);
        UUID current = userId.equals(CurrentActor.actorId()) ? CurrentActor.sessionIdOrNull() : null;
        return ResponseEntity.ok(ApiResponseWrapper.ok(toDtos(sessions.list(userId, tenant(), current))));
    }

    @Operation(summary = "Owner or store manager: close one of an employee's connected devices")
    @DeleteMapping("/api/v1/employees/{employeeId}/sessions/{sessionId}")
    public ResponseEntity<ApiResponseWrapper<Void>> closeEmployeeSession(@PathVariable UUID employeeId,
                                                                        @PathVariable UUID sessionId) {
        UUID userId = userOf(employeeId);
        sessions.close(CurrentActor.actorId(), userId, tenant(), sessionId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Owner or store manager: close all of an employee's connected devices")
    @DeleteMapping("/api/v1/employees/{employeeId}/sessions")
    public ResponseEntity<ApiResponseWrapper<Map<String, Integer>>> closeEmployeeSessions(
            @PathVariable UUID employeeId) {
        UUID userId = userOf(employeeId);
        UUID keep = userId.equals(CurrentActor.actorId()) ? CurrentActor.sessionIdOrNull() : null;
        int closed = sessions.closeOthers(CurrentActor.actorId(), userId, tenant(), keep, false);
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("closed", closed)));
    }

    /** Story 19.5b — the team rule (owner, or the delegate of the member's store), then the member's user id. */
    private UUID userOf(UUID employeeId) {
        UUID checked = teamScope.requireManageableUser(employeeId);
        return checked != null ? checked : sessions.employeeUserId(employeeId);
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
