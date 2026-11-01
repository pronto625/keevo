package com.keevo.admin.module.adapter.in.rest;

import com.keevo.admin.module.adapter.in.rest.dto.ModuleActivationDto;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleActivationRequest;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleCatalogueEntryDto;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleDeactivationDto;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleHistoryEntryDto;
import com.keevo.admin.module.adapter.in.rest.dto.JobStartedDto;
import com.keevo.admin.module.adapter.in.rest.dto.TenantModuleDto;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleJobs;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.TenantModuleStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * {@code MODULE_DEACTIVATION_FAILED} 500, {@code VALIDATION_ERROR} 422, {@code ADMIN_JOB_RUNNING} 409 — French
 * messages naming modules by display name.
 */
@Tag(name = "Admin — Modules", description = "Super Admin module catalogue and per-tenant activation")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin")
public class ModuleAdminController {

    private final ModuleLifecycle lifecycle;
    private final ModuleJobs jobs;

    public ModuleAdminController(ModuleLifecycle lifecycle, ModuleJobs jobs) {
        this.lifecycle = lifecycle;
        this.jobs = jobs;
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
            description = "409 MODULE_HAS_DEPENDENTS when active modules require it (details.modules: their display "
                    + "names), unless cascade=true: the active dependents are deactivated first, in reverse "
                    + "dependency order, in the same transaction. force=true writes DISABLED even when the module's "
                    + "deactivation hook fails (audited « Désactivation forcée »). 409 CORE_MODULE_REQUIRED for "
                    + "the core. Already disabled → 200.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/modules/{moduleId}/deactivate")
    public ResponseEntity<ApiResponseWrapper<ModuleDeactivationDto>> deactivate(
            @PathVariable UUID tenantId,
            @PathVariable String moduleId,
            @RequestParam(name = "cascade", defaultValue = "false") boolean cascade,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(ModuleDeactivationDto.from(lifecycle.deactivate(tenantId,
                moduleId, new ModuleLifecycle.DeactivationOptions(cascade, force), actor()))));
    }

    @Operation(summary = "Module history of a tenant: activations, deactivations, forced deactivations, packs applied",
            description = "Newest first, French labels, actor by name and phone, no identifier. Story 16.14: optional "
                    + "limit (1..500, default 100) and before (ISO instant: only entries strictly older, keyset on "
                    + "occurredAt) and beforeId (with before: compound keyset (occurredAt, id) — pass the occurredAt and id "
                    + "of the last entry received for the next page; a short page is the end); 400 "
                    + "VALIDATION_ERROR otherwise.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/modules/history")
    public ResponseEntity<ApiResponseWrapper<List<ModuleHistoryEntryDto>>> history(
            @PathVariable UUID tenantId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "beforeId", required = false) UUID beforeId) {
        CurrentActor.requireSuperAdmin();
        int size = limit != null ? limit : ModuleLifecycle.HISTORY_DEFAULT_LIMIT;
        if (size < 1 || size > ModuleLifecycle.HISTORY_MAX_LIMIT) {
            throw new InvalidRequestParameterException("limit", "limit must be between 1 and "
                    + ModuleLifecycle.HISTORY_MAX_LIMIT, "Le nombre d'entrées doit être compris entre 1 et "
                    + ModuleLifecycle.HISTORY_MAX_LIMIT);
        }
        Instant beforeAt = historyBefore(before);
        if (beforeId != null && beforeAt == null) {
            throw new InvalidRequestParameterException("before", "beforeId requires before",
                    "Date de référence manquante");
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(lifecycle.history(tenantId, size, beforeAt, beforeId)
                .stream().map(ModuleHistoryEntryDto::from).toList()));
    }

    /** The {@code before} cursor of the history: an ISO instant (offset accepted); {@code null} when absent. */
    private static Instant historyBefore(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidRequestParameterException("before", "before must be an ISO instant",
                    "Date de référence invalide");
        }
    }

    @Operation(summary = "Module catalogue (installed tenant modules)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/modules")
    public ResponseEntity<ApiResponseWrapper<List<ModuleCatalogueEntryDto>>> catalogue() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                lifecycle.catalogue().stream()
                        .map(m -> ModuleCatalogueEntryDto.from(m, jobs.hasMigrations(m.id()))).toList()));
    }

    @Operation(summary = "Modules of a tenant: effective state, stored status, dates, provisioned version",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/modules")
    public ResponseEntity<ApiResponseWrapper<List<TenantModuleDto>>> tenantModules(@PathVariable UUID tenantId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                lifecycle.tenantModules(tenantId).stream().map(TenantModuleDto::from).toList()));
    }

    @Operation(summary = "Backfill tenant_modules for every live tenant (Story 16.5) — background job (Story 16.4)",
            description = "Starts a MODULE_BACKFILL job and answers 202 {jobId} at once; follow it with GET "
                    + "/api/v1/admin/jobs/{jobId} (progress, then the report). The job writes, for each tenant, the "
                    + "paid-module rows equivalent to its plan (remaining trial kept, rows set by a super-admin "
                    + "untouched), then provisions the tables of every enabled module that ships migrations. "
                    + "Idempotent. dryRun=true (default) only reports what would be written. Tenants are named by "
                    + "their code. 409 ADMIN_JOB_RUNNING while another backfill job is queued or running.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/modules/backfill")
    public ResponseEntity<ApiResponseWrapper<JobStartedDto>> backfill(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponseWrapper.ok(
                new JobStartedDto(jobs.startBackfill(dryRun, CurrentActor.actorId()))));
    }

    @Operation(summary = "Provision a module's tables for every live tenant — background job (Story 16.4)",
            description = "Starts a MODULE_PROVISION_ALL job and answers 202 {jobId}. Tenants for which the module "
                    + "is not active are skipped; tenants already at the module's latest version are counted in "
                    + "upToDate without any migration run, unless force=true (full validate). 404 MODULE_NOT_FOUND, 422 VALIDATION_ERROR for a module without tables, 409 "
                    + "ADMIN_JOB_RUNNING while another provisioning job is queued or running.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/modules/{moduleId}/provision-all")
    public ResponseEntity<ApiResponseWrapper<JobStartedDto>> provisionAll(
            @PathVariable String moduleId,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponseWrapper.ok(
                new JobStartedDto(jobs.startProvisionAll(moduleId, force, CurrentActor.actorId()))));
    }

    // ── Parsing (adapter concern) ─────────────────────────────────────────────

    static ModuleLifecycle.Actor actor() {
        return new ModuleLifecycle.Actor(CurrentActor.actorId(), CurrentActor.isSuperAdmin());
    }

    static TenantModuleStatus parseStatus(String value) {
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
    static Instant parseExpiry(String value) {
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
