package com.keevo.shared.module.adapter.in.rest;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleLifecycle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * TenantModulesController — the current tenant's modules for its owner (Story 13.8a, spec-socle §10; feeds the
 * Flutter module UI of 15.1/15.6). OWNER only; the tenant is the one bound to the request ({@link TenantContext}).
 */
@Tag(name = "Tenant — Modules", description = "Modules of the current tenant and their subscription state")
@EntitlementExempt("liste des modules du commerçant")
@RestController
@RequestMapping("/api/v1/tenants/me")
public class TenantModulesController {

    private final ModuleLifecycle lifecycle;

    public TenantModulesController(ModuleLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * One module of the current tenant.
     *
     * @param state      {@code ENABLED} | {@code READ_ONLY} (subscription ended, grace running) | {@code DISABLED}
     * @param expiresAt  end of the trial / subscription ({@code null}: none)
     * @param graceUntil end of the read-only grace ({@code null}: none)
     */
    public record TenantModuleDto(String moduleId, String displayName, String state, Instant expiresAt,
                                  Instant graceUntil) {
    }

    @Operation(summary = "Modules of the current tenant with their state and subscription dates (owner)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/modules")
    public ResponseEntity<ApiResponseWrapper<List<TenantModuleDto>>> modules() {
        CurrentActor.requireOwner("Modules are restricted to the owner");
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        return ResponseEntity.ok(ApiResponseWrapper.ok(lifecycle.tenantModules(schema).stream()
                .map(v -> new TenantModuleDto(v.moduleId(), v.displayName(), v.state().name(), v.expiresAt(),
                        v.graceUntil()))
                .toList()));
    }
}
