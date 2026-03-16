package com.keevo.shared.infrastructure.web;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.dto.AuditEntryResponse;
import com.keevo.shared.infrastructure.web.dto.AuditPageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Query audit entries (paginated) with optional filters.
     *
     * <p>Pagination: {@code page} (0-based, default 0) and {@code size} (default 20).
     * Response includes {@code hasMore=true} when a next page exists.
     *
     * <p>Both entityType/entityId params are optional — a missing param is not an error (AC3).
     * Routing: both → filtered | entityType only → type filter | none → full tenant log.
     *
     * @param entityType optional entity type filter (e.g. "Product", "User")
     * @param entityId   optional entity UUID filter — ignored if entityType is absent
     * @param page       zero-based page index (default 0)
     * @param size       page size, max entries per response (default 20)
     * @return HTTP 200 with paginated audit entries sorted occurredAt DESC
     */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<AuditPageResponse>> getAuditHistory(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        AuditPort.AuditPage auditPage;

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
            auditPage = auditPort.findByEntityTypeAndEntityId(entityType, entityUuid, page, size);
        } else if (entityType != null) {
            // Branch 2: entity type only
            auditPage = auditPort.findByEntityType(entityType, page, size);
        } else {
            // Branch 3: no filters — full tenant log (safe: schema routing scopes to caller's tenant)
            auditPage = auditPort.findAll(page, size);
        }

        AuditPageResponse response = new AuditPageResponse(
                auditPage.entries().stream().map(AuditEntryResponse::fromRecord).toList(),
                auditPage.hasMore(),
                page,
                size
        );

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
