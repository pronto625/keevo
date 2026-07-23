package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final TokenRevocationPort tokenRevocationPort;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider,
                         ObjectMapper objectMapper,
                         TenantSchemaSyncService tenantSchemaSyncService,
                         JdbcTemplate jdbcTemplate,
                         TokenRevocationPort tokenRevocationPort) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.tenantSchemaSyncService = tenantSchemaSyncService;
        this.jdbcTemplate = jdbcTemplate;
        this.tokenRevocationPort = tokenRevocationPort;
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
            String firstName = jwtTokenProvider.extractFirstName(claims); // Story 14.10 — may be null

            // S5 / ARCH18 (B-HIGH-6, Story 12-5): validate tenantId for ALL roles before it
            // reaches any SQL path. The connection provider's search_path gate is bypassed by
            // fully-qualified "schema"."table" JDBC (revocation adapter, EMPLOYEE branch) — the
            // schema portion MUST be validated. RS256 mitigates forgery but defense-in-depth
            // requires no unvalidated claim ever enters a SQL string.
            String schema;
            try {
                schema = TenantSchema.validate(tenantId);
            } catch (IllegalArgumentException ex) {
                writeError(response, "TOKEN_INVALID");
                return;
            }

            // Story 12.2 — NFR12 (<5min session revocation, B-HIGH-5/S2): reject access tokens
            // issued BEFORE the membership's tokens_valid_after cutoff. Applies to ALL roles
            // (OWNER + EMPLOYEE), BEFORE TenantContext is set so a revoked token never seeds
            // the ThreadLocal. EMPLOYEE per-request DB check below remains as defense-in-depth.
            // NB: `iat` is a JWT NumericDate (seconds, RFC 7519) while `tva` is a timestamp(6)
            // (microseconds). Truncate tva to seconds before comparing — otherwise a freshly-
            // minted post-revoke token (revoke + mint within the same second, sub-second tva)
            // is falsely rejected as SESSION_REVOKED, locking the user out right after a
            // password change. Sub-second race is within NFR12's <5min tolerance.
            Instant tva = tokenRevocationPort.getTokensValidAfter(userId, tenantId);
            if (tva != null) {
                Instant iat = jwtTokenProvider.extractIssuedAt(claims);
                if (iat != null && iat.isBefore(tva.truncatedTo(ChronoUnit.SECONDS))) {
                    writeError(response, "SESSION_REVOKED");
                    return;
                }
            }

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

            // DELETION_PENDING tenants: block all write operations (Story 14.5, FR91).
            // Mirror of SUSPENDED above — same pattern, distinct domainCode for client diagnostics.
            // Read operations (GET, HEAD) are always permitted during the grace period.
            // EXCEPTION: /api/v1/account/delete/cancel is whitelisted (owner must be able to cancel).
            if ("DELETION_PENDING".equals(tenantStatus)
                    && isWriteMethod(request.getMethod())
                    && !request.getRequestURI().equals("/api/v1/account/delete/cancel")) {
                writeErrorWithStatus(response, "ACCOUNT_DELETION_PENDING",
                        jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // Sync tenant schema against public (no-op if already done this JVM lifetime)
            tenantSchemaSyncService.syncIfNeeded(tenantId);

            // Story 3.5 — EMPLOYEE runtime guards (DB-backed, runs AFTER TenantContext is set).
            // `schema` was validated above for ALL roles (Story 12-5 defense-in-depth).
            if ("EMPLOYEE".equals(role)) {
                try {
                    Map<String, Object> empRow = jdbcTemplate.queryForMap(
                            "SELECT store_id, status, password_change_required FROM \""
                            + schema + "\".employees WHERE user_id = ? LIMIT 1", userId);

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
            // Story 14.10: store firstName + storeId in AuthDetails for controller access
            if ("EMPLOYEE".equals(role)) {
                UUID storeId = jwtTokenProvider.extractStoreId(claims);
                auth.setDetails(AuthDetails.employee(firstName, storeId));
            } else {
                auth.setDetails(AuthDetails.owner(firstName));
            }
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
