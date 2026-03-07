package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Date;
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

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(), tenantSchemaSyncService);
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
        Claims mockClaims = buildClaims(userId, "KV-ABC123", "OWNER");
        when(jwtTokenProvider.parseToken("valid.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("KV-ABC123");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(tenantSchemaSyncService).syncIfNeeded("KV-ABC123");
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
        Claims mockClaims = buildClaims(userId, "KV-ABC123", "OWNER");
        when(jwtTokenProvider.parseToken("valid.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("KV-ABC123");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        doThrow(new RuntimeException("downstream error")).when(filterChain).doFilter(any(), any());

        // Should not throw — exception is propagated but TenantContext must be cleared
        assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
                .isInstanceOf(RuntimeException.class);

        // TenantContext must be cleared (verified via TenantContext.getCurrentTenant() == null)
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    // ── Suspension guard ───────────────────────────────────────────────────

    @Test
    @DisplayName("SUSPENDED tenant + POST returns 403 ACCOUNT_SUSPENDED")
    void should_return_403_when_tenant_suspended_and_write_method() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/stores");
        request.addHeader("Authorization", "Bearer suspended.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        Claims mockClaims = buildClaims(userId, "KV-SUSP01", "OWNER");
        when(jwtTokenProvider.parseToken("suspended.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("KV-SUSP01");
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
        Claims mockClaims = buildClaims(userId, "KV-SUSP02", "OWNER");
        when(jwtTokenProvider.parseToken("suspended.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("KV-SUSP02");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(mockClaims)).thenReturn("SUSPENDED");

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
        Claims mockClaims = buildClaims(userId, "KV-ACT01", "OWNER");
        when(jwtTokenProvider.parseToken("active.jwt.token")).thenReturn(mockClaims);
        when(jwtTokenProvider.extractTenantId(mockClaims)).thenReturn("KV-ACT01");
        when(jwtTokenProvider.extractRole(mockClaims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(mockClaims)).thenReturn("ACTIVE");

        filter.doFilterInternal(request, response, filterChain);

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
