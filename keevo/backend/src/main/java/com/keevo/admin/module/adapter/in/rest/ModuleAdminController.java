package com.keevo.admin.module.adapter.in.rest;

import com.keevo.admin.module.adapter.in.rest.dto.ModuleActivationDto;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleActivationRequest;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleCatalogueEntryDto;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleDeactivationDto;
import com.keevo.admin.module.adapter.in.rest.dto.TenantModuleDto;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.TenantModuleStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ModuleAdminController — super-admin module lifecycle of a tenant (Story 13.8a, spec-socle §10, MOD-FR2/3/16).
 *
 * <p>ZERO business logic: parsing, then {@link ModuleLifecycle}. {@link CurrentActor#requireSuperAdmin()} in every
 * method (second barrier behind the SecurityConfig {@code /api/v1/admin/**} rule). Errors: {@code TENANT_NOT_FOUND}
 * / {@code MODULE_NOT_FOUND} 404, {@code MODULE_ACTIVATION_BLOCKED} / {@code MODULE_HAS_DEPENDENTS} /
 * {@code CORE_MODULE_REQUIRED} 409, {@code MODULE_PROVISIONING_FAILED} / {@code MODULE_ACTIVATION_FAILED} /
 * {@code MODULE_DEACTIVATION_FAILED} 500, {@code VALIDATION_ERROR} 422 — French
 * messages naming modules by display name.
 */
@Tag(name = "Admin — Modules", description = "Super Admin module catalogue and per-tenant activation")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin")
public class ModuleAdminController {

    private final ModuleLifecycle lifecycle;

    public ModuleAdminController(ModuleLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Operation(summary = "Activate a module for a tenant (missing dependencies first, tables provisioned)",
            description = "Body optional: status ACTIVE (default) or TRIAL, expiresAt (ISO instant or date, future). "
                    + "Paid modules are granted. Already enabled with nothing to change → 200 with an empty "
                    + "'activated' list.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/modules/{moduleId}/activate")
    public ResponseEntity<ApiResponseWrapper<ModuleActivationDto>> activate(
            @PathVariable UUID tenantId,
            @PathVariable String moduleId,
            @RequestBody(required = false) ModuleActivationRequest request) {
        CurrentActor.requireSuperAdmin();
        ModuleLifecycle.ActivationRequest activation = new ModuleLifecycle.ActivationRequest(
                parseStatus(request != null ? request.status() : null),
                parseExpiry(request != null ? request.expiresAt() : null));
        return ResponseEntity.ok(ApiResponseWrapper.ok(ModuleActivationDto.from(
                lifecycle.activate(tenantId, moduleId, activation, actor()))));
    }

    @Operation(summary = "Deactivate a module for a tenant (row DISABLED, tables and data kept)",
            description = "409 MODULE_HAS_DEPENDENTS when active modules require it, 409 CORE_MODULE_REQUIRED for "
                    + "the core. Already disabled → 200.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/modules/{moduleId}/deactivate")
    public ResponseEntity<ApiResponseWrapper<ModuleDeactivationDto>> deactivate(
            @PathVariable UUID tenantId,
            @PathVariable String moduleId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(ModuleDeactivationDto.from(
                lifecycle.deactivate(tenantId, moduleId, actor()))));
    }

    @Operation(summary = "Module catalogue (installed tenant modules)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/modules")
    public ResponseEntity<ApiResponseWrapper<List<ModuleCatalogueEntryDto>>> catalogue() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                lifecycle.catalogue().stream().map(ModuleCatalogueEntryDto::from).toList()));
    }

    @Operation(summary = "Modules of a tenant: effective state, stored status, dates, provisioned version",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/modules")
    public ResponseEntity<ApiResponseWrapper<List<TenantModuleDto>>> tenantModules(@PathVariable UUID tenantId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                lifecycle.tenantModules(tenantId).stream().map(TenantModuleDto::from).toList()));
    }

    // ── Parsing (adapter concern) ─────────────────────────────────────────────

    private static ModuleLifecycle.Actor actor() {
        return new ModuleLifecycle.Actor(CurrentActor.actorId(), CurrentActor.isSuperAdmin());
    }

    private static TenantModuleStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return TenantModuleStatus.ACTIVE;
        }
        return switch (value.trim()) {
            case "ACTIVE" -> TenantModuleStatus.ACTIVE;
            case "TRIAL" -> TenantModuleStatus.TRIAL;
            default -> throw invalid("status", "Statut invalide : choisissez Actif ou Essai");
        };
    }

    /** ISO instant / date-time with offset, or a plain date (end of that day, WAT); blank → no expiry. */
    private static Instant parseExpiry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a plain date
        }
        try {
            return LocalDate.parse(text).plusDays(1).atStartOfDay(KeevoZones.WAT).toInstant().minusSeconds(1);
        } catch (DateTimeParseException e) {
            throw invalid("expiresAt", "Date de fin invalide");
        }
    }

    private static DomainException invalid(String field, String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }
}
