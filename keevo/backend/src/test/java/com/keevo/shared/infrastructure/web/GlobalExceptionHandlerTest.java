package com.keevo.shared.infrastructure.web;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
