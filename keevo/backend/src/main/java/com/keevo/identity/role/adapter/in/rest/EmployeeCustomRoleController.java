package com.keevo.identity.role.adapter.in.rest;

import com.keevo.identity.role.application.service.CustomRoleAssignmentService;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Story 19.14 — the owner gives a member one of his roles, or takes it back ({@code roleId: null}). Owner only: a
 * delegated team manager never hands out rights.
 */
@Tag(name = "Roles", description = "Owner-defined roles and permissions")
@EntitlementExempt("core: rôles de l'équipe")
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeCustomRoleController {

    /** {@code roleId}: a role of {@code GET /roles}, or {@code null} to go back to the built-in role's defaults. */
    public record AssignRoleRequest(UUID roleId) {
    }

    private final CustomRoleAssignmentService assignments;

    public EmployeeCustomRoleController(CustomRoleAssignmentService assignments) {
        this.assignments = assignments;
    }

    @Operation(summary = "Give a member a custom role (or take it back with null)")
    @PutMapping("/{employeeId}/custom-role")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<Void>> assign(@PathVariable UUID employeeId,
                                                           @RequestBody(required = false) AssignRoleRequest request) {
        assignments.assign(TenantContext.getCurrentTenant(), CurrentActor.actorId(), employeeId,
                request == null ? null : request.roleId());
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }
}
