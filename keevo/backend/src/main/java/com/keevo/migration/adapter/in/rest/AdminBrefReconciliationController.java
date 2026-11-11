package com.keevo.migration.adapter.in.rest;

import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.port.in.BrefReconciliationUseCase;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Story 24.8 — the reconciliation reports of the Bref migration (super-admin): the latest report of an import batch,
 * a new reconciliation of a completed batch, the latest report of a Bref owner. Every completed import is reconciled
 * automatically; the POST re-runs it (after a correction, or to check again). SUPER_ADMIN by the
 * {@code /api/v1/admin/**} URL rule and checked again here. Reports hold aggregates and French labels only.
 */
@Tag(name = "Admin — Bref migration", description = "Super Admin import of Bref owners (epic 24)")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/migration")
public class AdminBrefReconciliationController {

    private final BrefReconciliationUseCase useCase;

    public AdminBrefReconciliationController(BrefReconciliationUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "Latest reconciliation report of an import batch",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/batches/{batchId}/reconciliation")
    public ResponseEntity<ApiResponseWrapper<ReconciliationReport>> report(@PathVariable UUID batchId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.report(batchId)));
    }

    @Operation(summary = "Reconcile a completed import batch again (report stored, owner blocked on a gap)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/batches/{batchId}/reconciliation")
    public ResponseEntity<ApiResponseWrapper<ReconciliationReport>> reconcile(@PathVariable UUID batchId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(useCase.reconcile(batchId, CurrentActor.actorId())));
    }

    @Operation(summary = "Latest reconciliation report of a Bref owner",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/bref/owners/{ownerId}/reconciliation")
    public ResponseEntity<ApiResponseWrapper<ReconciliationReport>> latestForOwner(@PathVariable UUID ownerId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.latestForOwner(ownerId)));
    }
}
