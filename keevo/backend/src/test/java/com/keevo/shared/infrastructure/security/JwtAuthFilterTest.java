package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JwtAuthFilterTest — TDD RED tests for JwtAuthFilter (TenantJwtFilter).
 *
 * <p>Written BEFORE the implementation (Story 1.3 TDD requirement).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter (TenantJwtFilter)")
class JwtAuthFilterTest {

    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock FilterChain filterChain;
    @Mock TenantSchemaSyncService tenantSchemaSyncService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock TokenRevocationPort tokenRevocationPort;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(),
                tenantSchemaSyncService, null, tokenRevocationPort);
    }

    /**
     * Constructs a filter with a mocked {@link JdbcTemplate} for EMPLOYEE-branch tests
     * that exercise the {@code jdbcTemplate.queryForMap} call. OWNER tests use the
     * default {@link #setUp()} (jdbcTemplate=null) and are unaffected.
     */
    private JwtAuthFilter filterWithJdbc() {
        return new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(),
                tenantSchemaSyncService, jdbcTemplate, tokenRevocationPort);
    }

    // ── Missing Authorization header ───────────────────────────────────────

    @Test
    @DisplayName("missing Authorization header returns 401 UNAUTHORIZED without calling filter chain")
    void should_return_401_when_no_auth_header() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Authorization header without Bearer prefix returns 401 UNAUTHORIZED")
    void should_return_401_when_no_bearer_prefix() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    // ── Expired JWT ────────────────────────────────────────────────────────

    @Test
    @DisplayName("expired JWT returns 401 TOKEN_EXPIRED without calling filter chain")
    void should_return_401_token_expired_for_expired_jwt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer expired.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.parseToken("expired.jwt.token"))
                .thenThrow(new ExpiredJwtException(null, null, "Token expired"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_EXPIRED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    // ── Malformed JWT ──────────────────────────────────────────────────────

    @Test
    @DisplayName("malformed JWT returns 401 UNAUTHORIZED without calling filter chain")
    void should_return_401_for_malformed_jwt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not.a.valid.jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.parseToken("not.a.valid.jwt"))
                .thenThrow(new JwtException("invalid signature"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    // ── Valid JWT ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid JWT sets TenantContext and calls filterChain.doFilter")
    void should_set_tenant_context_and_proceed_for_valid_jwt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "kv_abc123", "OWNER");
        when(jwtTokenProvider.parseToken("valid.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("kv_abc123");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(tenantSchemaSyncService).syncIfNeeded("kv_abc123");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("syncIfNeeded is NOT called when JWT is invalid")
    void should_not_sync_schema_when_jwt_is_invalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.parseToken("bad.jwt.token"))
                .thenThrow(new JwtException("invalid"));

        filter.doFilterInternal(request, response, filterChain);

        verify(tenantSchemaSyncService, never()).syncIfNeeded(any());
    }

    @Test
    @DisplayName("TenantContext is cleared in finally block even when filter chain throws")
    void should_clear_tenant_context_even_on_exception() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "kv_abc123", "OWNER");
        when(jwtTokenProvider.parseToken("valid.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("kv_abc123");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        doThrow(new RuntimeException("downstream error")).when(filterChain).doFilter(any(), any());

        // Should not throw — exception is propagated but TenantContext must be cleared
        assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
                .isInstanceOf(RuntimeException.class);

        // TenantContext must be cleared (verified via TenantContext.getCurrentTenant() == null)
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    // ── Login token scope rejection (Story 1.7 — AC4) ────────────────────────────

    @Test
    @DisplayName("loginToken with scope=login_pending is rejected with 401 TOKEN_INVALID")
    void should_reject_login_token_used_as_access_token() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/subscriptions/status");
        request.addHeader("Authorization", "Bearer login.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        // buildClaims with placeholder tenant — won't be accessed (early return on scope check)
        Claims mockClaims = buildClaims(userId, "placeholder", "placeholder");
        when(jwtTokenProvider.parseToken("login.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractScope(mockClaims)).thenReturn("login_pending");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_INVALID");
        verify(filterChain, never()).doFilter(any(), any());
    }

    // ── Suspension guard ───────────────────────────────────────────────────

    @Test
    @DisplayName("SUSPENDED tenant + POST returns 403 ACCOUNT_SUSPENDED")
    void should_return_403_when_tenant_suspended_and_write_method() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/stores");
        request.addHeader("Authorization", "Bearer suspended.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "kv_susp01", "OWNER");
        when(jwtTokenProvider.parseToken("suspended.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("kv_susp01");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(mockClaims)).thenReturn("SUSPENDED");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCOUNT_SUSPENDED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("SUSPENDED tenant + GET passes through (reads always allowed)")
    void should_allow_get_for_suspended_tenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/subscription/me");
        request.addHeader("Authorization", "Bearer suspended.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "kv_susp02", "OWNER");
        when(jwtTokenProvider.parseToken("suspended.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("kv_susp02");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(mockClaims)).thenReturn("SUSPENDED");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ── DELETION_PENDING tenant (Story 14.5, FR91) ─────────────────────────

    @Test
    @DisplayName("DELETION_PENDING tenant + POST returns 403 ACCOUNT_DELETION_PENDING")
    void should_return_403_when_tenant_deletion_pending_and_write_method() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/stores");
        request.addHeader("Authorization", "Bearer deletion.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "kv_del001", "OWNER");
        when(jwtTokenProvider.parseToken("deletion.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("kv_del001");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(mockClaims)).thenReturn("DELETION_PENDING");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCOUNT_DELETION_PENDING");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("DELETION_PENDING tenant + GET passes through (reads always allowed)")
    void should_allow_get_for_deletion_pending_tenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer deletion.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "kv_del002", "OWNER");
        when(jwtTokenProvider.parseToken("deletion.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("kv_del002");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(mockClaims)).thenReturn("DELETION_PENDING");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ACTIVE tenant + POST passes through normally")
    void should_allow_post_for_active_tenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/stores");
        request.addHeader("Authorization", "Bearer active.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "kv_act001", "OWNER");
        when(jwtTokenProvider.parseToken("active.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("kv_act001");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(mockClaims)).thenReturn("ACTIVE");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ── EMPLOYEE tenantId validation (Story 12.5 — AC3/AC4) ─────────────────

    @Test
    @DisplayName("EMPLOYEE token with invalid tenantId format → 401 TOKEN_INVALID, no SQL call")
    void shouldRejectTokenWithInvalidTenantIdFormat() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer emp.invalid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims claims = buildClaims(userId, "kv_evil\"; DROP TABLE--", "EMPLOYEE");
        when(jwtTokenProvider.parseToken("emp.invalid.jwt.token")).thenReturn(claims);
        when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
        when(jwtTokenProvider.extractTenantId(claims)).thenReturn("kv_evil\"; DROP TABLE--");
        when(jwtTokenProvider.extractRole(claims)).thenReturn("EMPLOYEE");
        when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");

        filterWithJdbc().doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_INVALID");
        verify(filterChain, never()).doFilter(any(), any());
        verify(jdbcTemplate, never()).queryForMap(anyString(), any());
    }

    @Test
    @DisplayName("EMPLOYEE token with valid kv_xxxxxx tenantId → 200, SQL uses validated schema")
    void shouldAcceptValidTenantId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer emp.valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Claims claims = buildClaims(userId, "kv_abc123", "EMPLOYEE");
        when(jwtTokenProvider.parseToken("emp.valid.jwt.token")).thenReturn(claims);
        when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
        when(jwtTokenProvider.extractTenantId(claims)).thenReturn("kv_abc123");
        when(jwtTokenProvider.extractRole(claims)).thenReturn("EMPLOYEE");
        when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");
        when(jwtTokenProvider.extractStoreId(claims)).thenReturn(storeId);

        Map<String, Object> empRow = new HashMap<>();
        empRow.put("store_id", storeId);
        empRow.put("status", "ACTIVE");
        empRow.put("password_change_required", false);
        when(jdbcTemplate.queryForMap(contains("\"kv_abc123\"."), eq(userId)))
                .thenReturn(empRow);

        filterWithJdbc().doFilterInternal(request, response, filterChain);

        verify(jdbcTemplate).queryForMap(contains("\"kv_abc123\"."), eq(userId));
        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ── Story 12.2 — Session revocation cutoff (AC1/AC2) ──────────────────

    @Test
    @DisplayName("token issued BEFORE tokens_valid_after → 401 SESSION_REVOKED, no filterChain")
    void shouldRejectTokenIssuedBeforeRevocation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer owner.revoked.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims claims = buildClaims(userId, "kv_abc123", "OWNER");
        when(jwtTokenProvider.parseToken("owner.revoked.jwt.token")).thenReturn(claims);
        when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
        when(jwtTokenProvider.extractTenantId(claims)).thenReturn("kv_abc123");
        when(jwtTokenProvider.extractRole(claims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");
        // Cutoff AFTER iat → revoked
        when(tokenRevocationPort.getTokensValidAfter(userId, "kv_abc123"))
                .thenReturn(Instant.now().plus(1, ChronoUnit.MINUTES));
        when(jwtTokenProvider.extractIssuedAt(claims))
                .thenReturn(Instant.now().minus(10, ChronoUnit.MINUTES));

        filterWithJdbc().doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SESSION_REVOKED");
        verify(filterChain, never()).doFilter(any(), any());
        verify(tokenRevocationPort).getTokensValidAfter(userId, "kv_abc123");
    }

    @Test
    @DisplayName("token issued AFTER tokens_valid_after → 200, filterChain proceeds")
    void shouldAcceptTokenIssuedAfterRevocation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer owner.valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims claims = buildClaims(userId, "kv_abc123", "OWNER");
        when(jwtTokenProvider.parseToken("owner.valid.jwt.token")).thenReturn(claims);
        when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
        when(jwtTokenProvider.extractTenantId(claims)).thenReturn("kv_abc123");
        when(jwtTokenProvider.extractRole(claims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");
        // Cutoff BEFORE iat → allow
        when(tokenRevocationPort.getTokensValidAfter(userId, "kv_abc123"))
                .thenReturn(Instant.now().minus(10, ChronoUnit.MINUTES));
        when(jwtTokenProvider.extractIssuedAt(claims))
                .thenReturn(Instant.now().minus(1, ChronoUnit.MINUTES));

        filterWithJdbc().doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Claims buildClaims(UUID userId, String tenantId, String role) {
        return io.jsonwebtoken.Jwts.claims()
                .subject(userId.toString())
                .add("tenantId", tenantId)
                .add("role", role)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .build();
    }
}
