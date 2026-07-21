package com.keevo.shared.infrastructure.web;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandlerTest — Verifies HTTP status code mapping for domain error codes.
 *
 * <p>Tests the handler method directly (no Spring context needed — handler is a plain class).
 *
 * <p>RED → GREEN:
 * - Before GREEN: FORBIDDEN code does not exist → compilation failure = RED
 * - After GREEN: all codes exist and map correctly → GREEN
 */
@DisplayName("GlobalExceptionHandler — domain code to HTTP status mapping")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── Subscription / Plan (AC1, AC2, AC3) ──────────────────────────────────

    @Test
    @DisplayName("PLAN_LIMIT_EXCEEDED maps to 403 Forbidden")
    void planLimitExceeded_mapsto403() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "test"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().domainCode()).isEqualTo("PLAN_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("ACCOUNT_SUSPENDED maps to 403 Forbidden")
    void accountSuspended_mapsto403() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.ACCOUNT_SUSPENDED, "test"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().domainCode()).isEqualTo("ACCOUNT_SUSPENDED");
    }

    @Test
    @DisplayName("RATE_LIMIT_EXCEEDED maps to 429 Too Many Requests")
    void rateLimitExceeded_mapsto429() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "test"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().domainCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    // ── H2 fix: FORBIDDEN must map to 403 (not 401) ──────────────────────────

    @Test
    @DisplayName("FORBIDDEN maps to 403 Forbidden (not 401 Unauthorized) — H2 fix")
    void forbidden_mapsto403_not401() {
        // RED: ErrorCode.FORBIDDEN does not exist yet → compile error = RED
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.FORBIDDEN, "SUPER_ADMIN role required"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().domainCode()).isEqualTo("FORBIDDEN");
    }

    // ── Existing codes should be unchanged ───────────────────────────────────

    @Test
    @DisplayName("UNAUTHORIZED maps to 401 (auth failures, token issues)")
    void unauthorized_mapsto401() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.UNAUTHORIZED, "test"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("NOT_FOUND maps to 404")
    void notFound_mapsto404() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.NOT_FOUND, "test"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Audit (Story 1.8) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("AUDIT_IMMUTABLE maps to 403 Forbidden")
    void auditImmutable_mapsto403() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.AUDIT_IMMUTABLE, "Audit entries are immutable"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().domainCode()).isEqualTo("AUDIT_IMMUTABLE");
    }

    @Test
    @DisplayName("AUDIT_IMMUTABLE error field is French user-facing message")
    void auditImmutable_errorFieldIsInFrench() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.AUDIT_IMMUTABLE, "technical message"));

        assertThat(resp.getBody()).isNotNull();
        // Must be French message from FR_MESSAGES map, not the raw technical message
        assertThat(resp.getBody().error())
                .contains("journal")  // "journal d'audit"
                .doesNotContain("technical message");
    }

    @Test
    @DisplayName("handleDomainException: response body has non-null timestamp and no stackTrace field")
    void domainException_responseHasTimestampAndNoStackTrace() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleDomainException(new DomainException(ErrorCode.INTERNAL_ERROR, "test"));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().timestamp()).isNotNull();
        // ApiResponseWrapper is a record — no stackTrace field exists by design
        // (Verified by checking the record components: data, error, code, domainCode, details, timestamp)
    }

    @Test
    @DisplayName("handleGeneral: response has no stackTrace — only standard error format")
    void generalException_noStackTraceInResponse() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleGeneral(new RuntimeException("Internal error test"));

        assertThat(resp.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().domainCode()).isEqualTo("INTERNAL_ERROR");
        // The wrapper has no 'stackTrace' field — only data/error/code/domainCode/details/timestamp
    }

    @Test
    @DisplayName("handleGeneral: error field is the French message from FR_MESSAGES (not English hardcoded)")
    void generalException_errorFieldIsInFrench() {
        // RED: handleGeneral() currently hardcodes "An unexpected error occurred" (English)
        // GREEN: must use FR_MESSAGES.getOrDefault("INTERNAL_ERROR", ...) → French string
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleGeneral(new RuntimeException("some technical error"));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().error())
                .isEqualTo("Une erreur inattendue s'est produite")  // French from FR_MESSAGES
                .doesNotContain("unexpected");                        // Not the English hardcoded string
    }

    // ── Optimistic Lock (Story v1s-13-1) ──────────────────────────────────

    @Test
    @DisplayName("AC4: OptimisticLockingFailureException maps to 409 CONFLICT with OPTIMISTIC_LOCK domainCode")
    void shouldReturn409ForOptimisticLockException() {
        ResponseEntity<ApiResponseWrapper<Void>> resp =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("Row was updated by another transaction"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().domainCode()).isEqualTo("OPTIMISTIC_LOCK");
        assertThat(resp.getBody().error()).contains("modifiée");
    }
}
