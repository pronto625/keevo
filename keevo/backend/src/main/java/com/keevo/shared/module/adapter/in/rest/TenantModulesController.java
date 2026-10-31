package com.keevo.shared.module.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleManifest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TenantModulesController — the current tenant's modules (Story 13.8a, spec-socle §10; feeds the Flutter
 * {@code entitlementsProvider} of 15.1 and the « Mes modules » screen of 15.6). OWNER and EMPLOYEE (Story 15.1):
 * employees gate their UI too, so they read every module's state; the subscription dates ({@code expiresAt},
 * {@code graceUntil}) stay the owner's business and are {@code null} for them. The tenant is the one bound to the
 * request ({@link TenantContext}). Since Story 15.6 each module also carries whether it is part of the free offer and its
 * category (manifest) for every role, and its stored subscription {@code status} for the owner only (like the
 * dates), so the owner's « Mes modules » screen can group and label it. {@code platform} modules are never listed
 * ({@link ModuleLifecycle#tenantModules(String)} only covers the tenant catalogue).
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
     * @param expiresAt  end of the trial / subscription ({@code null}: none, or a non-owner caller)
     * @param graceUntil end of the read-only grace ({@code null}: none, or a non-owner caller)
     * @param status     stored subscription status ({@code TRIAL} | {@code ACTIVE} | {@code EXPIRED} |
     *                   {@code DISABLED} | {@code AVAILABLE}; {@code null}: never subscribed / core, or a non-owner
     *                   caller)
     * @param free       part of the free offer (manifest {@code free})
     * @param category   manifest category code ({@code core} | {@code business} | {@code premium})
     */
    public record TenantModuleDto(String moduleId, String displayName, String state, Instant expiresAt,
                                  Instant graceUntil, String status, boolean free, String category) {
    }

    @Operation(summary = "Modules of the current tenant with their state; subscription dates for the owner only",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/modules")
    public ResponseEntity<ApiResponseWrapper<List<TenantModuleDto>>> modules() {
        boolean owner = CurrentActor.isOwner();
        if (!owner && !CurrentActor.isEmployee()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Modules are restricted to the tenant's users");
        }
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        Map<String, ModuleManifest> manifests = lifecycle.catalogue().stream()
                .collect(Collectors.toMap(ModuleManifest::id, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(ApiResponseWrapper.ok(lifecycle.tenantModules(schema).stream()
                .map(v -> {
                    ModuleManifest manifest = manifests.get(v.moduleId());
                    return new TenantModuleDto(v.moduleId(), v.displayName(), v.state().name(),
                            owner ? v.expiresAt() : null, owner ? v.graceUntil() : null,
                            owner && v.status() != null ? v.status().name() : null,
                            manifest != null && manifest.free(),
                            manifest != null ? manifest.category().code() : null);
                })
                .toList()));
    }
}
