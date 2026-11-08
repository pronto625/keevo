package com.keevo.admin.user.adapter.in.rest;

import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.user.domain.model.AdminUserViews.DeviceSession;
import com.keevo.admin.user.domain.model.AdminUserViews.UserItem;
import com.keevo.admin.user.domain.model.AdminUserViews.UserStats;
import com.keevo.admin.user.domain.port.in.AdminUserUseCase;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Story 20.13 — super-admin users read API inherited from Bref ({@code GET /users}, {@code /users/stats},
 * {@code /users/:id}, {@code /users/:id/devices}). Read-only: status, plan and deletion go through the existing account
 * endpoints ({@code /admin/tenants/{tenantId}/suspend|reactivate|plan/*|force-delete}). SUPER_ADMIN checked explicitly
 * behind the SecurityConfig URL rule.
 */
@Tag(name = "Admin — Users", description = "Super Admin platform users")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserUseCase useCase;

    public AdminUserController(AdminUserUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "Platform users (keyset)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<CursorPage<UserItem>>> listUsers(
            @RequestParam(required = false) String role, @RequestParam(required = false) String active,
            @RequestParam(required = false) String tenantId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.listUsers(
                new AdminUserUseCase.UserQuery(role, active, tenantId, q, cursor, limit))));
    }

    @Operation(summary = "Platform user figures", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/stats")
    public ResponseEntity<ApiResponseWrapper<UserStats>> getStats(
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String role, @RequestParam(required = false) String active) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getStats(
                new AdminUserUseCase.StatsQuery(from, to, role, active))));
    }

    @Operation(summary = "One platform user", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponseWrapper<UserItem>> getUser(@PathVariable String userId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getUser(userId)));
    }

    @Operation(summary = "Connected devices of a user", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{userId}/devices")
    public ResponseEntity<ApiResponseWrapper<List<DeviceSession>>> listDevices(@PathVariable String userId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.listDevices(userId)));
    }
}
