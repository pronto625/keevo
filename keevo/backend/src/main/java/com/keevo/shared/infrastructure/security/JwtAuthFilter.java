package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JwtAuthFilter — JWT authentication filter (implements TenantJwtFilter role).
 *
 * <p>GoF Pattern: Template Method — extends {@link OncePerRequestFilter} which
 * defines the template (execute exactly once per request), this class fills in
 * the {@link #doFilterInternal} hook.
 *
 * <p>Security contract:
 * <ul>
 *   <li>Validates RS256 JWT from "Authorization: Bearer" header</li>
 *   <li>Sets {@link TenantContext} via ThreadLocal before business logic</li>
 *   <li>Clears {@link TenantContext} in {@code finally} block — prevents ThreadLocal leaks</li>
 *   <li>Returns 401 JSON error for missing/expired/malformed tokens</li>
 * </ul>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Paths that must never require a JWT — mirrors SecurityConfig.PUBLIC_PATHS. */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/actuator/health",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/select-tenant"   // Story 1.7 — AC7: two-step login step 2 is public
    );

    /** Ant-style paths (wildcards) that are also public. */
    private static final List<String> PUBLIC_ANT_PATHS = List.of(
            "/v3/api-docs/**",
            "/swagger-ui/**"
    );

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Paths where passwordChangeRequired check is skipped (employee CAN access these). */
    private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of(
            "/api/v1/auth/change-password"
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final TenantSchemaSyncService tenantSchemaSyncService;
    private final JdbcTemplate jdbcTemplate;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider,
                         ObjectMapper objectMapper,
                         TenantSchemaSyncService tenantSchemaSyncService,
                         JdbcTemplate jdbcTemplate) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.tenantSchemaSyncService = tenantSchemaSyncService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Skip JWT validation entirely for public endpoints.
     * Spring's OncePerRequestFilter calls this before doFilterInternal.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (PUBLIC_PATHS.contains(path)) return true;
        return PUBLIC_ANT_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // Reject requests without Bearer token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeError(response, "UNAUTHORIZED");
            return;
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = jwtTokenProvider.parseToken(token);

            // Story 1.7 — AC4: login tokens (scope=login_pending) must NEVER grant API access.
            // A loginToken is a short-lived intermediate credential used only for /auth/select-tenant.
            String scope = jwtTokenProvider.extractScope(claims);
            if ("login_pending".equals(scope)) {
                writeError(response, "TOKEN_INVALID");
                return;
            }

            String tenantId = jwtTokenProvider.extractTenantId(claims);
            String role = jwtTokenProvider.extractRole(claims);
            UUID userId = jwtTokenProvider.extractUserId(claims);
            String tenantStatus = jwtTokenProvider.extractTenantStatus(claims);

            // Set multi-tenant context for JPA routing
            TenantContext.setCurrentTenant(tenantId);

            // SUSPENDED tenants: block all write operations (manual admin lockout).
            // Read operations (GET, HEAD) are always permitted — data is preserved.
            // Note: natural trial/premium expiry does NOT set SUSPENDED — it downgrades to FREE.
            if ("SUSPENDED".equals(tenantStatus)
                    && isWriteMethod(request.getMethod())) {
                writeErrorWithStatus(response, "ACCOUNT_SUSPENDED",
                        jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // Sync tenant schema against public (no-op if already done this JVM lifetime)
            tenantSchemaSyncService.syncIfNeeded(tenantId);

            // Story 3.5 — EMPLOYEE runtime guards (DB-backed, runs AFTER TenantContext is set).
            if ("EMPLOYEE".equals(role)) {
                try {
                    Map<String, Object> empRow = jdbcTemplate.queryForMap(
                            "SELECT store_id, status, password_change_required FROM \""
                            + tenantId + "\".employees WHERE user_id = ? LIMIT 1", userId);

                    if ("INACTIVE".equals(empRow.get("status"))) {
                        writeError(response, "ACCOUNT_INACTIVE");
                        return;
                    }

                    UUID jwtStoreId = jwtTokenProvider.extractStoreId(claims);
                    UUID dbStoreId = (UUID) empRow.get("store_id");
                    // Reject if storeId is absent from JWT (pre-3.5 token) or doesn't match DB.
                    if (!java.util.Objects.equals(dbStoreId, jwtStoreId)) {
                        writeError(response, "STORE_REASSIGNED");
                        return;
                    }

                    Boolean pwdChangeReq = (Boolean) empRow.get("password_change_required");
                    String path = request.getRequestURI();
                    if (Boolean.TRUE.equals(pwdChangeReq)
                            && !PASSWORD_CHANGE_ALLOWED.contains(path)) {
                        writeErrorWithStatus(response, "PASSWORD_CHANGE_REQUIRED",
                                HttpServletResponse.SC_FORBIDDEN);
                        return;
                    }
                } catch (EmptyResultDataAccessException ex) {
                    writeError(response, "ACCOUNT_INACTIVE");
                    return;
                }
            }

            // Populate Spring Security context
            var auth = new UsernamePasswordAuthenticationToken(
                    userId, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            SecurityContextHolder.getContext().setAuthentication(auth);

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            writeError(response, "TOKEN_EXPIRED");
        } catch (JwtException e) {
            writeError(response, "UNAUTHORIZED");
        } finally {
            // MANDATORY: clear ThreadLocal to prevent tenant leakage in thread-pool
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private void writeErrorWithStatus(HttpServletResponse response,
                                      String domainCode,
                                      int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = objectMapper.writeValueAsString(Map.of("domainCode", domainCode));
        response.getWriter().write(body);
    }

    private void writeError(HttpServletResponse response, String domainCode) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = objectMapper.writeValueAsString(Map.of("domainCode", domainCode));
        response.getWriter().write(body);
    }
}
