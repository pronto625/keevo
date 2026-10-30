package com.keevo.shared.module.adapter.in.rest;

import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.Extension;
import com.keevo.shared.module.ExtensionRegistry;
import com.keevo.shared.module.application.ModulePermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * TenantExtensionsController — the contributions of an extension point (a UI slot) usable by the caller in the
 * current tenant (Story 14.5, MOD-FR10, UX-DR3; rendered by the app's {@code ExtensionSlot} in 15.3).
 *
 * <p>The list is {@link ExtensionRegistry#contributions} (declaring module readable, contributing modules ENABLED)
 * filtered on the caller's roles: a contribution requiring a {@link Extension#permission() permission} is kept only
 * when {@link ModulePermissions#permissionsOf} grants it. An unknown or malformed point id → 404, nothing echoed.
 */
@Tag(name = "Tenant — Extensions", description = "Contributions of the active modules to an extension point")
@EntitlementExempt("points d'extension du commerçant")
@RestController
@RequestMapping("/api/v1/tenants/me")
public class TenantExtensionsController {

    private final ExtensionRegistry extensions;
    private final ModulePermissions permissions;

    public TenantExtensionsController(ExtensionRegistry extensions, ModulePermissions permissions) {
        this.extensions = extensions;
        this.permissions = permissions;
    }

    /**
     * One contribution of the slot.
     *
     * @param id     contribution id (stable key)
     * @param label  French label shown to the user
     * @param order  position within the slot (ascending)
     * @param action client routing key of the action
     */
    public record ExtensionDto(String id, String label, int order, String action) {
    }

    @Operation(summary = "Contributions of the active modules to an extension point, for the caller",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @GetMapping("/extensions/{pointId}")
    public ResponseEntity<ApiResponseWrapper<List<ExtensionDto>>> extensions(@PathVariable String pointId) {
        if (pointId == null || !extensions.declaredPoints().contains(pointId)) {
            // built here: a DomainException(NOT_FOUND, msg) would surface as "NOT_FOUND: msg" (no French mapping)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.error(
                    "Point d'extension inconnu", HttpStatus.NOT_FOUND.name(), ErrorCode.NOT_FOUND.name(), null));
        }
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        Set<String> held = permissions.permissionsOf(ModulePermissions.rolesOf(CurrentActor.authentication()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(extensions.contributions(pointId, schema).stream()
                .filter(e -> e.permission() == null || held.contains(e.permission()))
                .map(e -> new ExtensionDto(e.id(), e.label(), e.order(), e.action()))
                .toList()));
    }

}
