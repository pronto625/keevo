package com.keevo.migration.adapter.in.rest;

import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.port.in.BrefOwnerImportUseCase;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Story 24.2 — the minimal super-admin API of the Bref migration tool (the waves dashboard is 24.9): import one Bref
 * owner from a source, read a batch. SUPER_ADMIN by the {@code /api/v1/admin/**} URL rule and checked again here.
 * The import is synchronous: 201 with the batch, whatever its outcome (COMPLETED, BLOCKED with its cases, FAILED with
 * its label) — the HTTP errors are for a request that could not start (source, owner, export, concurrent run).
 */
@Tag(name = "Admin — Bref migration", description = "Super Admin import of Bref owners (epic 24)")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/migration")
public class AdminBrefMigrationController {

    /** Body of the import route: {@code EXPORT} (api-bref export file) or {@code DATABASE} (restored Bref copy). */
    public record ImportRequest(String source) {
    }

    private final BrefOwnerImportUseCase useCase;
    /** Story 24.9 — the batch list. */
    private final com.keevo.migration.domain.port.in.BrefCutoverUseCase cutovers;

    public AdminBrefMigrationController(BrefOwnerImportUseCase useCase,
                                        com.keevo.migration.domain.port.in.BrefCutoverUseCase cutovers) {
        this.useCase = useCase;
        this.cutovers = cutovers;
    }

    @Operation(summary = "Import (or complete the import of) a Bref owner: identity, company, shops, team",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/bref/owners/{ownerId}/imports")
    public ResponseEntity<ApiResponseWrapper<MigrationBatchResponse>> importOwner(
            @PathVariable UUID ownerId, @RequestBody(required = false) ImportRequest body) {
        CurrentActor.requireSuperAdmin();
        MigrationBatch batch = useCase.importOwner(ownerId, body == null ? null : body.source(),
                CurrentActor.actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(MigrationBatchResponse.of(batch)));
    }

    @Operation(summary = "List the import batches, newest first (keyset: after = the last batch id of the page)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/batches")
    public ResponseEntity<ApiResponseWrapper<java.util.List<MigrationBatchResponse>>> batches(
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID after,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(cutovers.batches(after, limit).stream()
                .map(MigrationBatchResponse::of).toList()));
    }

    @Operation(summary = "Read an import batch", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/batches/{batchId}")
    public ResponseEntity<ApiResponseWrapper<MigrationBatchResponse>> batch(@PathVariable UUID batchId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(MigrationBatchResponse.of(useCase.batch(batchId))));
    }
}
