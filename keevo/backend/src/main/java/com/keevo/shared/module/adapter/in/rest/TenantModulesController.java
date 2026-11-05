package com.keevo.shared.module.adapter.in.rest;

import com.keevo.identity.api.NavigationPreset;
import com.keevo.identity.api.TenantSaleFlowQueryPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.OwnerModuleActivation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * {@code graceUntil}) stay the owner's business and are {@code null} for them — except, since Story 16.14, the
 * {@code status} and {@code graceUntil} of a module in read-only grace, so the employee's banner can show its end date. The tenant is the one bound to the
 * request ({@link TenantContext}). Since Story 15.6 each module also carries whether it is part of the free offer and its
 * category (manifest) for every role, and its stored subscription {@code status} for the owner only (like the
 * dates), so the owner's « Mes modules » screen can group and label it. {@code platform} modules are never listed
 * ({@link ModuleLifecycle#tenantModules(String)} only covers the tenant catalogue).
 *
 * <p>Story 19.13 — interface profile: each module also carries {@code inPlan} (the plan includes it),
 * {@code activated} (the owner's choice, {@code true} when never disabled) and {@code ownerToggleable} (the owner may
 * toggle it in « Mes modules »); an owner-disabled module has {@code state = DISABLED}. The response's
 * {@code meta.navigationPreset} ({@code CAISSE} | {@code DOCUMENT}) is derived from the default sale flow. The owner
 * toggles a plan module with {@code PUT /modules/{moduleId}/activation}.
 */
@Tag(name = "Tenant — Modules", description = "Modules of the current tenant and their subscription state")
@EntitlementExempt("liste des modules du commerçant")
@RestController
@RequestMapping("/api/v1/tenants/me")
public class TenantModulesController {

    private final ModuleLifecycle lifecycle;
    private final OwnerModuleActivation ownerActivation;
    private final TenantSaleFlowQueryPort saleFlows;

    public TenantModulesController(ModuleLifecycle lifecycle, OwnerModuleActivation ownerActivation,
                                   TenantSaleFlowQueryPort saleFlows) {
        this.lifecycle = lifecycle;
        this.ownerActivation = ownerActivation;
        this.saleFlows = saleFlows;
    }

    /**
     * One module of the current tenant.
     *
     * @param state      {@code ENABLED} | {@code READ_ONLY} (subscription ended, grace running) | {@code DISABLED}
     * @param expiresAt  end of the trial / subscription ({@code null}: none, or a non-owner caller)
     * @param graceUntil end of the read-only grace ({@code null}: none, or a non-owner caller on a module not in grace)
     * @param status     stored subscription status ({@code TRIAL} | {@code ACTIVE} | {@code EXPIRED} |
     *                   {@code DISABLED} | {@code AVAILABLE}; {@code null}: never subscribed / core, or a non-owner
     *                   caller on a module not in grace)
     * @param free       part of the free offer (manifest {@code free})
     * @param category   manifest category code ({@code core} | {@code business} | {@code premium})
     * @param inPlan          Story 19.13 — the plan includes the module (« Mes modules » vs « Mon plan »)
     * @param activated       Story 19.13 — the owner's choice ({@code true} when never disabled)
     * @param ownerToggleable Story 19.13 — the owner may activate / disable it in « Mes modules »
     */
    public record TenantModuleDto(String moduleId, String displayName, String state, Instant expiresAt,
                                  Instant graceUntil, String status, boolean free, String category, boolean inPlan,
                                  boolean activated, boolean ownerToggleable) {
    }

    @Operation(summary = "Modules of the current tenant with their state; subscription dates for the owner only",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/modules")
    public ResponseEntity<ApiResponseWrapper<List<TenantModuleDto>>> modules() {
        boolean owner = CurrentActor.isOwner();
        if (!owner && !CurrentActor.isStoreMember()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Modules are restricted to the tenant's users");
        }
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        Map<String, ModuleManifest> manifests = manifests();
        List<TenantModuleDto> modules = lifecycle.tenantModules(schema).stream()
                .map(v -> dto(v, manifests.get(v.moduleId()), owner))
                .toList();
        NavigationPreset preset = saleFlows.find(schema)
                .map(TenantSaleFlowQueryPort.TenantSaleFlowView::navigationPreset)
                .orElse(NavigationPreset.CAISSE);
        return ResponseEntity.ok(ApiResponseWrapper.ok(modules, Map.of("navigationPreset", preset.name())));
    }

    @Operation(summary = "Owner: activate or disable a module of the plan for the whole company (« Mes modules »)",
            description = "Body {\"activated\": true|false}. OWNER only. Tenant-wide, audited, no data deleted. "
                    + "409 MODULE_NOT_IN_PLAN (outside the plan: « Mon plan »), MODULE_NOT_TOGGLEABLE, "
                    + "MODULE_HAS_DEPENDENTS (disable those first), MODULE_REQUIREMENT_DISABLED (activate those "
                    + "first); 404 MODULE_NOT_FOUND; 422 VALIDATION_ERROR. Same value: 200, nothing changed. "
                    + "Returns the module as the list shows it.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @EntitlementExempt("core: Mes modules")
    @PutMapping("/modules/{moduleId}/activation")
    public ResponseEntity<ApiResponseWrapper<TenantModuleDto>> setActivation(
            @PathVariable String moduleId,
            @RequestBody(required = false) Map<String, Object> body) {
        CurrentActor.requireOwner("OWNER role required");
        Object activated = body != null ? body.get("activated") : null;
        if (!(activated instanceof Boolean value)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Indiquez si le module est activé");
        }
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        ownerActivation.setActivation(schema, moduleId, value, CurrentActor.actorId());
        Map<String, ModuleManifest> manifests = manifests();
        return lifecycle.tenantModules(schema).stream()
                .filter(v -> v.moduleId().equals(moduleId))
                .findFirst()
                .map(v -> ResponseEntity.ok(ApiResponseWrapper.ok(dto(v, manifests.get(v.moduleId()), true))))
                .orElseThrow(() -> new DomainException(ErrorCode.MODULE_NOT_FOUND, "Module introuvable"));
    }

    private Map<String, ModuleManifest> manifests() {
        return lifecycle.catalogue().stream()
                .collect(Collectors.toMap(ModuleManifest::id, Function.identity(), (a, b) -> a));
    }

    private static TenantModuleDto dto(ModuleLifecycle.TenantModuleView v, ModuleManifest manifest, boolean owner) {
        // 16.14 — an employee also needs the grace date of a read-only module (banner « jusqu'au … »)
        boolean inGrace = v.state() == ModuleState.READ_ONLY;
        boolean showGrace = owner || inGrace;
        return new TenantModuleDto(v.moduleId(), v.displayName(), v.state().name(),
                owner ? v.expiresAt() : null, showGrace ? v.graceUntil() : null,
                showGrace && v.status() != null ? v.status().name() : null,
                manifest != null && manifest.free(),
                manifest != null ? manifest.category().code() : null,
                v.inPlan(), v.ownerActivated(), manifest != null && manifest.ownerCanToggle());
    }
}
