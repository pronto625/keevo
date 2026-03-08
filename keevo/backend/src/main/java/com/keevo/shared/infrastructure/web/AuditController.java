package com.keevo.shared.infrastructure.web;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.dto.AuditEntryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * AuditController — Query endpoint for immutable audit log entries.
 *
 * <p>Story 1.8 — GET /api/v1/audit with optional filters.
 *
 * <p><b>Three routing branches (AC3):</b>
 * <ul>
 *   <li>Both {@code entityType} and {@code entityId} present → filtered query</li>
 *   <li>Only {@code entityType} → type-only filter query</li>
 *   <li>No params → full tenant log (safe: TenantContext scopes to caller's schema)</li>
 * </ul>
 *
 * <p><b>Immutability protection (AC2):</b> DELETE and PUT endpoints throw
 * {@code DomainException(AUDIT_IMMUTABLE)} and return HTTP 403. No modifications possible.
 *
 * <p><b>Anti-pattern prevention:</b> NO local {@code @ExceptionHandler} — all exceptions
 * flow to {@code GlobalExceptionHandler} ({@code @RestControllerAdvice}).
 * See Story 1.4 post-mortem: local handlers are invisible to unit tests.
 *
 * <p>Cross-cutting concern — lives in {@code shared/infrastructure/web/} (not feature module).
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditPort auditPort;

    public AuditController(AuditPort auditPort) {
        this.auditPort = auditPort;
    }

    /**
     * Query audit entries with optional filters.
     *
     * <p>Both params are optional (required=false) — a missing param is not an error (AC3).
     * Routing: both → filtered | entityType only → type filter | none → full tenant log.
     *
     * @param entityType optional entity type filter (e.g. "Product", "User")
     * @param entityId   optional entity UUID filter — ignored if entityType is absent
     * @return HTTP 200 with list of audit entries sorted occurredAt DESC
     */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<AuditEntryResponse>>> getAuditHistory(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId) {

        List<AuditPort.AuditEntryRecord> records;

        if (entityType != null && entityId != null) {
            // Branch 1: both filters present — validate UUID format before delegating
            UUID entityUuid;
            try {
                entityUuid = UUID.fromString(entityId);
            } catch (IllegalArgumentException e) {
                log.debug("Invalid entityId UUID format received: {}", entityId);
                throw new DomainException(ErrorCode.VALIDATION_ERROR,
                        "entityId doit être un UUID valide (format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)");
            }
            records = auditPort.findByEntityTypeAndEntityId(entityType, entityUuid);
        } else if (entityType != null) {
            // Branch 2: entity type only
            records = auditPort.findByEntityType(entityType);
        } else {
            // Branch 3: no filters — full tenant log (safe: schema routing scopes to caller's tenant)
            records = auditPort.findAll();
        }

        List<AuditEntryResponse> response = records.stream()
                .map(AuditEntryResponse::fromRecord)
                .toList();

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * DELETE — immutability guard. Returns 403 AUDIT_IMMUTABLE for any delete attempt.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        throw new DomainException(ErrorCode.AUDIT_IMMUTABLE,
                "Audit entries are immutable and cannot be deleted");
    }

    /**
     * PUT — immutability guard. Returns 403 AUDIT_IMMUTABLE for any update attempt.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id,
                                       @RequestBody(required = false) Object body) {
        throw new DomainException(ErrorCode.AUDIT_IMMUTABLE,
                "Audit entries are immutable and cannot be modified");
    }
}
