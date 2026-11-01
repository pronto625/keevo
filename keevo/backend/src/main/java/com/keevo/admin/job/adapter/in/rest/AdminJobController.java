package com.keevo.admin.job.adapter.in.rest;

import com.keevo.admin.job.adapter.in.rest.dto.AdminJobDto;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.job.AdminJobs;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AdminJobController — super-admin background jobs (Story 16.4): status, progress and report of the module backfill,
 * provisioning and plan-profile re-sync jobs started by the « Modules » console.
 *
 * <p>ZERO business logic: parsing, then {@link AdminJobs}. {@link CurrentActor#requireSuperAdmin()} in every method.
 * Errors: {@code ADMIN_JOB_NOT_FOUND} 404 « Tâche introuvable », {@code VALIDATION_ERROR} 422 for an unknown kind.
 */
@Tag(name = "Admin — Jobs", description = "Super Admin background jobs: progress and reports")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/jobs")
public class AdminJobController {

    static final int LIST_SIZE = 20;

    private final AdminJobs jobs;

    public AdminJobController(AdminJobs jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "A job: status (QUEUED, RUNNING, SUCCEEDED, FAILED), progress, report, French error",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponseWrapper<AdminJobDto>> get(@PathVariable UUID jobId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(jobs.find(jobId).map(AdminJobDto::from)
                .orElseThrow(() -> new DomainException(ErrorCode.ADMIN_JOB_NOT_FOUND, "Tâche introuvable"))));
    }

    @Operation(summary = "The 20 latest jobs, newest first; kind = MODULE_BACKFILL | MODULE_PROVISION_ALL | "
            + "PROFILE_RESYNC (optional)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<AdminJobDto>>> list(
            @RequestParam(name = "kind", required = false) String kind) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                jobs.latest(parseKind(kind), LIST_SIZE).stream().map(AdminJobDto::from).toList()));
    }

    private static AdminJobs.Kind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AdminJobs.Kind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String message = "Type de tâche inconnu";
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message, Map.of("kind", message));
        }
    }
}
