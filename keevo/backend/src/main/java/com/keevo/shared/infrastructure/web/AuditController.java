package com.keevo.shared.infrastructure.web;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.dto.AuditEntryResponse;
import com.keevo.shared.infrastructure.web.dto.AuditPageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditController — Query endpoint for immutable audit log entries.
 *
 * <p>Story 1.8 — GET /api/v1/audit with optional filters.
 * Story v1s-18-6 — OWNER-only, filtered + cursor-paged list, {@code GET /{id}} detail. No
 * filter → full tenant log (safe: TenantContext scopes to the caller's schema).
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

    static final int MAX_SIZE = 100;

    /** Story v1s-18-7 — max length of the object label search text. */
    static final int MAX_QUERY_LENGTH = 100;

    private final AuditPort auditPort;

    public AuditController(AuditPort auditPort) {
        this.auditPort = auditPort;
    }

    /**
     * Query audit entries — OWNER only (FR98), newest first ({@code occurredAt DESC, id DESC}).
     *
     * <p>Filters (all optional, Story v1s-18-6): {@code from}/{@code to} (ISO-8601 instants,
     * inclusive), {@code storeId}, {@code userId}, {@code action}, {@code entityType},
     * {@code entityId}, {@code q} (object label search, trimmed, max 100 chars — Story v1s-18-7).
     * Paging: keyset {@code cursor} (from {@code nextCursor}) or legacy
     * {@code page} (0-based offset, used only without cursor). {@code size} 1..100, default 20.
     *
     * @return HTTP 200 with entries, {@code hasMore}, {@code page}, {@code size}, {@code nextCursor};
     *         400 VALIDATION_ERROR on a malformed parameter; 403 for a non-OWNER actor
     */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<AuditPageResponse>> getAuditHistory(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        CurrentActor.requireOwner("Audit log is restricted to the owner");

        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestParameterException("size", "size must be between 1 and " + MAX_SIZE);
        }
        if (page < 0) {
            throw new InvalidRequestParameterException("page", "page must be >= 0");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidRequestParameterException("page", "page * size exceeds the maximum offset");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from must not be after to");
        }
        String search = blankToNull(q == null ? null : q.trim());
        if (search != null && search.length() > MAX_QUERY_LENGTH) {
            throw new InvalidRequestParameterException("q",
                    "q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        String effectiveCursor = cursor == null || cursor.isBlank() ? null : cursor;
        if (effectiveCursor != null) {
            try {
                AuditPort.AuditCursor.decode(effectiveCursor);
            } catch (IllegalArgumentException e) {
                log.debug("Malformed audit cursor received");
                throw new InvalidRequestParameterException("cursor", "cursor is malformed");
            }
        }

        AuditPort.AuditQuery query = new AuditPort.AuditQuery(
                from, to, storeId, userId, blankToNull(action), blankToNull(entityType), entityId, search);
        AuditPort.AuditSlice slice = auditPort.search(query, effectiveCursor, page, size);

        AuditPageResponse response = new AuditPageResponse(
                slice.entries().stream().map(AuditEntryResponse::fromRecord).toList(),
                slice.hasMore(),
                page,
                size,
                slice.nextCursor()
        );
        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * Full detail of one entry — OWNER only. 404 {@code AUDIT_ENTRY_NOT_FOUND} when unknown in
     * the caller's tenant (schema routing scopes the lookup).
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<AuditEntryResponse>> getAuditEntry(@PathVariable UUID id) {
        CurrentActor.requireOwner("Audit log is restricted to the owner");
        AuditPort.AuditEntryRecord entry = auditPort.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.AUDIT_ENTRY_NOT_FOUND,
                        "Audit entry not found"));
        return ResponseEntity.ok(ApiResponseWrapper.ok(AuditEntryResponse.fromRecord(entry)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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
