package com.keevo.migration.adapter.in.rest;

import com.keevo.migration.domain.model.Cutover;
import com.keevo.migration.domain.model.CutoverOwner;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.CutoverReport;
import com.keevo.migration.domain.port.in.BrefCutoverUseCase;
import com.keevo.migration.domain.port.in.BrefCutoverUseCase.Acceptance;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Story 24.9 — the super-admin API of the one-shot Bref cutover (ADR-10): open and run it (asynchronous: 202, then
 * poll), follow it (status, live counts, archived report, owners by keyset), give the « go » or roll back. SUPER_ADMIN by
 * the {@code /api/v1/admin/**} URL rule and checked again here. Every label is French; ids are handles only.
 */
@Tag(name = "Admin — Bref cutover", description = "Super Admin one-shot cutover of every Bref owner (epic 24, ADR-10)")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/migration/cutovers")
public class AdminBrefCutoverController {

    /** Body of the start route. */
    public record StartRequest(String source, Integer parallelism) {
    }

    /** Body of the « go »: the confirmation phrase and the signed exclusion list. */
    public record ActivationRequest(String confirmation, List<AcceptedOwnerRequest> acceptedOwners) {
    }

    /** An owner accepted despite its gap or block, with the documented reason. */
    public record AcceptedOwnerRequest(UUID ownerId, String reason) {
    }

    /** Body of the rollback. */
    public record RollbackRequest(String confirmation) {
    }

    /** Body of an owner's recovery after the « go » (reprise 11/11/2026). */
    public record RecoveryRequest(String confirmation) {
    }

    /** A cutover as the dashboard shows it. */
    public record CutoverResponse(UUID id, String source, String sourceLabel, String status, String statusLabel,
                                  int parallelism, int runs, Instant startedAt, Instant finishedAt,
                                  Instant heartbeatAt, Instant activatedAt, Instant rolledBackAt, String errorLabel,
                                  List<CutoverReport.StatusCount> progress, List<AcceptedOwnerResponse> acceptedOwners,
                                  CutoverReport report, List<RecoveredOwnerResponse> recoveredOwners) {

        static CutoverResponse of(Cutover c) {
            return new CutoverResponse(c.id(), c.source().name(), c.source().label(), c.status().name(),
                    c.status().label(), c.parallelism(), c.runs(), c.startedAt(), c.finishedAt(), c.heartbeatAt(),
                    c.activatedAt(), c.rolledBackAt(), c.errorLabel(), c.progress(),
                    c.acceptedOwners().stream().map(a -> new AcceptedOwnerResponse(a.ownerId(), a.reason(),
                            a.acceptedAt())).toList(), c.report(),
                    c.recoveredOwners().stream().map(r -> new RecoveredOwnerResponse(r.ownerId(), r.label(),
                            r.recoveredAt())).toList());
        }
    }

    /** An owner recovered after the « go » ({@code label} « Compte n » to display; {@code ownerId} is a handle). */
    public record RecoveredOwnerResponse(UUID ownerId, String label, Instant recoveredAt) {
    }

    /** An accepted owner of the « go » ({@code ownerId} is a handle). */
    public record AcceptedOwnerResponse(UUID ownerId, String reason, Instant acceptedAt) {
    }

    /** One owner line ({@code label} « Compte n » and {@code companyName} to display; ids are handles). */
    public record CutoverOwnerResponse(UUID brefOwnerId, int position, String label, String companyName,
                                       String status, String statusLabel, UUID batchId, String reason,
                                       Integer importMs, Integer reconcileMs, Instant startedAt, Instant finishedAt) {

        static CutoverOwnerResponse of(CutoverOwner o) {
            return new CutoverOwnerResponse(o.brefOwnerId(), o.position(), o.label(), o.companyName(),
                    o.status().name(), o.status().label(), o.batchId(), o.reason(), o.importMs(), o.reconcileMs(),
                    o.startedAt(), o.finishedAt());
        }
    }

    private final BrefCutoverUseCase useCase;

    public AdminBrefCutoverController(BrefCutoverUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "Open a cutover over the restored Bref database and run it in the background",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<CutoverResponse>> start(@RequestBody(required = false) StartRequest body) {
        CurrentActor.requireSuperAdmin();
        Cutover c = useCase.start(body == null ? null : body.source(), body == null ? null : body.parallelism(),
                CurrentActor.actorId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponseWrapper.ok(CutoverResponse.of(c)));
    }

    @Operation(summary = "List the cutovers, newest first", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<CutoverResponse>>> list(
            @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "20") int limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.cutovers(after, limit).stream()
                .map(CutoverResponse::of).toList()));
    }

    @Operation(summary = "Read a cutover: status, live counts and archived report",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{cutoverId}")
    public ResponseEntity<ApiResponseWrapper<CutoverResponse>> cutover(@PathVariable UUID cutoverId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(CutoverResponse.of(useCase.cutover(cutoverId))));
    }

    @Operation(summary = "The owners of a cutover, in listing order (keyset on position)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{cutoverId}/owners")
    public ResponseEntity<ApiResponseWrapper<List<CutoverOwnerResponse>>> owners(
            @PathVariable UUID cutoverId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer after, @RequestParam(defaultValue = "100") int limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.owners(cutoverId, status(status), after, limit)
                .stream().map(CutoverOwnerResponse::of).toList()));
    }

    @Operation(summary = "Run the cutover again (owners not reconciled yet, owners added since)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{cutoverId}/runs")
    public ResponseEntity<ApiResponseWrapper<CutoverResponse>> resume(@PathVariable UUID cutoverId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponseWrapper.ok(CutoverResponse.of(
                useCase.resume(cutoverId, CurrentActor.actorId()))));
    }

    @Operation(summary = "The « go »: activate every imported company (refused while an owner blocks, unless accepted)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{cutoverId}/activation")
    public ResponseEntity<ApiResponseWrapper<CutoverResponse>> activate(
            @PathVariable UUID cutoverId, @RequestBody(required = false) ActivationRequest body) {
        CurrentActor.requireSuperAdmin();
        List<Acceptance> accepted = body == null || body.acceptedOwners() == null ? List.of()
                : body.acceptedOwners().stream().map(a -> a == null ? null : new Acceptance(a.ownerId(), a.reason()))
                .toList();
        Cutover c = useCase.activate(cutoverId, body == null ? null : body.confirmation(), accepted,
                CurrentActor.actorId());
        return ResponseEntity.ok(ApiResponseWrapper.ok(CutoverResponse.of(c)));
    }

    @Operation(summary = "Roll back the cutover before the « go »: remove every imported company, in the background",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{cutoverId}/rollback")
    public ResponseEntity<ApiResponseWrapper<CutoverResponse>> rollback(
            @PathVariable UUID cutoverId, @RequestBody(required = false) RollbackRequest body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponseWrapper.ok(CutoverResponse.of(
                useCase.rollback(cutoverId, body == null ? null : body.confirmation(), CurrentActor.actorId()))));
    }

    @Operation(summary = "Roll back ONE owner before the « go » (its company removed, its line back to pending; "
            + "relaunch the cutover to import it again)", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{cutoverId}/owners/{ownerId}/rollback")
    public ResponseEntity<ApiResponseWrapper<CutoverResponse>> rollbackOwner(
            @PathVariable UUID cutoverId, @PathVariable UUID ownerId,
            @RequestBody(required = false) RollbackRequest body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(CutoverResponse.of(useCase.rollbackOwner(cutoverId, ownerId,
                body == null ? null : body.confirmation(), CurrentActor.actorId()))));
    }

    @Operation(summary = "After the « go », recover ONE owner left out: import it again from the frozen copy, "
            + "reconcile it and activate its company alone, in the background (202; 200 when already activated)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{cutoverId}/owners/{ownerId}/recovery")
    public ResponseEntity<ApiResponseWrapper<CutoverResponse>> recoverOwner(
            @PathVariable UUID cutoverId, @PathVariable UUID ownerId,
            @RequestBody(required = false) RecoveryRequest body) {
        CurrentActor.requireSuperAdmin();
        BrefCutoverUseCase.Recovery r = useCase.recoverOwner(cutoverId, ownerId,
                body == null ? null : body.confirmation(), CurrentActor.actorId());
        return ResponseEntity.status(r.started() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResponseWrapper.ok(CutoverResponse.of(r.cutover())));
    }

    private static CutoverOwnerStatus status(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(CutoverOwnerStatus.values()).filter(s -> s.name().equals(code.strip())).findFirst()
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.VALIDATION_FAILED,
                        "Statut de compte inconnu"));
    }
}
