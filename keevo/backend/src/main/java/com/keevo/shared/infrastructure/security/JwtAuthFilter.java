package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.activity.UserActivityRecorder;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import com.keevo.shared.infrastructure.security.access.AccessState;
import com.keevo.shared.infrastructure.security.access.AccessStateService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /**
     * Paths that never require a JWT: {@link SecurityConfig#PUBLIC_PATHS}, the single source.
     * A separate copy here had drifted — forgot/reset-password were public in SecurityConfig but
     * answered 401 here, so password reset never worked (device test).
     */
    private static final List<String> PUBLIC_PATHS = List.of(SecurityConfig.PUBLIC_PATHS);

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Story 11.1 — MDC keys written to every structured (JSON) log line of an authenticated request. */
    static final String MDC_TENANT_ID = "tenantId";
    static final String MDC_USER_ID = "userId";

    /** Paths where passwordChangeRequired check is skipped (employee CAN access these). */
    private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of(
            "/api/v1/auth/change-password"
    );

    /**
     * Story 16.11 — read-only path also allowed during a forced password change: the app learns
     * the flag (and onboarding / account status) from the profile.
     */
    private static final String PROFILE_PATH = "/api/v1/auth/profile";

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final TenantSchemaSyncService tenantSchemaSyncService;
    /** Story 16.11 — server-side access state (role, store, statuses, revocation, sessions). */
    private final AccessStateService accessStates;
    /** Story 9-4 — DAU/MAU tracking (asynchronous, never throws). */
    private final UserActivityRecorder userActivityRecorder;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider,
                         ObjectMapper objectMapper,
                         TenantSchemaSyncService tenantSchemaSyncService,
                         AccessStateService accessStates,
                         UserActivityRecorder userActivityRecorder) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.tenantSchemaSyncService = tenantSchemaSyncService;
        this.accessStates = java.util.Objects.requireNonNull(accessStates, "accessStates");
        this.userActivityRecorder = java.util.Objects.requireNonNull(userActivityRecorder, "userActivityRecorder");
    }

    /**
     * Skip JWT validation entirely for public endpoints.
     * Spring's OncePerRequestFilter calls this before doFilterInternal.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
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

            // Story 16.11 — the token proves identity only (sub, tenantId, sid, iat, exp). Role,
            // store, membership/employee status, password flag, account status, revocation cutoff
            // and session validity are read from the database (cached ≤ 30 s, evicted on every
            // change on all instances). Legacy claims of V1 tokens are never read here.
            String tenantId = jwtTokenProvider.extractTenantId(claims);
            UUID userId = jwtTokenProvider.extractUserId(claims);
            UUID sessionId = jwtTokenProvider.extractSessionId(claims);

            // S5 / ARCH18 (Story 12-5): validate tenantId before it reaches any SQL path. The
            // super-admin system account (schema "public") is the only exception; its role is
            // checked against the database below.
            boolean platformSchema = TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantId);
            String schema;
            try {
                schema = platformSchema ? tenantId : TenantSchema.validate(tenantId);
            } catch (IllegalArgumentException ex) {
                writeError(response, "TOKEN_INVALID");
                return;
            }

            // Story 16.11 — a token without sid predates device sessions (60-day tokens): closing
            // its device could not cut it. Past the current access-token lifetime it is treated as
            // expired; installed V1 apps renew on TOKEN_EXPIRED (their refresh rows carry a session).
            if (sessionId == null) {
                Instant iat = jwtTokenProvider.extractIssuedAt(claims);
                if (iat == null || iat.plus(jwtTokenProvider.accessTokenLifetime())
                        .plusSeconds(JwtTokenProvider.CLOCK_SKEW_SECONDS).isBefore(Instant.now())) {
                    writeError(response, "TOKEN_EXPIRED");
                    return;
                }
            }

            AccessState state;
            try {
                state = accessStates.get(userId, schema);
            } catch (DataAccessException ex) {
                // Fail closed: without the access state the role cannot be known.
                log.error("Access state lookup failed for user {} tenant {}", userId, tenantId, ex);
                writeErrorWithStatus(response, "INTERNAL_ERROR",
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }
            if (state == null) {
                writeError(response, "ACCOUNT_INACTIVE");
                return;
            }
            // Story 16.11 — the device session was closed (« Appareils connectés », sign-out,
            // replay): this device must sign in again. Tokens without sid are bounded by the
            // access-token lifetime check above.
            try {
                if (sessionId != null) {
                    // A session missing from the cached state reloads it: go on with the fresh one.
                    state = accessStates.withOpenSession(userId, schema, state, sessionId);
                    if (state == null) {
                        writeError(response, "SESSION_REVOKED");
                        return;
                    }
                }
            } catch (DataAccessException ex) {
                log.error("Session lookup failed for user {} tenant {}", userId, tenantId, ex);
                writeErrorWithStatus(response, "INTERNAL_ERROR",
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }

            if ("DELETED".equals(state.tenantStatus())) {
                writeError(response, "ACCOUNT_INACTIVE");
                return;
            }
            String role = state.role();
            if (platformSchema != TenantSchema.isPlatformAdminSession(schema, role)) {
                // "public" is the super-admin's account only; a super-admin never acts in a shop.
                writeError(response, "TOKEN_INVALID");
                return;
            }

            // Story 12.2 — tokens issued before the membership's tokens_valid_after cutoff are
            // revoked. `iat` is in seconds (RFC 7519), `tva` in microseconds: truncate tva so a
            // token minted in the same second right after a revoke is not refused.
            Instant tva = state.tokensValidAfter();
            if (tva != null) {
                Instant iat = jwtTokenProvider.extractIssuedAt(claims);
                if (iat != null && iat.isBefore(tva.truncatedTo(ChronoUnit.SECONDS))) {
                    writeError(response, "SESSION_REVOKED");
                    return;
                }
            }
            if (!state.membershipActive()) {
                writeError(response, "ACCOUNT_INACTIVE");
                return;
            }
            // Set multi-tenant context for JPA routing
            TenantContext.setCurrentTenant(tenantId);
            // Story 11.1 — per-tenant log correlation. Seeded only after the revocation checks, so a
            // revoked token never reaches the MDC. Removed in the same finally as TenantContext.
            MDC.put(MDC_TENANT_ID, tenantId);
            MDC.put(MDC_USER_ID, userId.toString());

            String tenantStatus = state.tenantStatus();
            // SUSPENDED tenants: block all write operations (manual admin lockout).
            // Read operations (GET, HEAD) are always permitted — data is preserved.
            if ("SUSPENDED".equals(tenantStatus) && isWriteMethod(request.getMethod())) {
                writeErrorWithStatus(response, "ACCOUNT_SUSPENDED", HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // DELETION_PENDING tenants: block all write operations (Story 14.5, FR91), except the
            // cancellation itself. Reads stay permitted during the grace period.
            if ("DELETION_PENDING".equals(tenantStatus)
                    && isWriteMethod(request.getMethod())
                    && !request.getRequestURI().equals("/api/v1/account/delete/cancel")) {
                writeErrorWithStatus(response, "ACCOUNT_DELETION_PENDING", HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // Sync tenant schema against public (no-op if already done this JVM lifetime)
            tenantSchemaSyncService.syncIfNeeded(tenantId);

            // Story 3.5 — EMPLOYEE runtime guards, from the employee row of the access state.
            AccessState.EmployeeState employee = state.employee();
            if ("EMPLOYEE".equals(role)) {
                if (employee == null || "INACTIVE".equals(employee.status())) {
                    writeError(response, "ACCOUNT_INACTIVE");
                    return;
                }
                // Story 16.11 — compatibility only: an installed V1 app holding a token whose legacy
                // storeId claim differs from the database renews once (STORE_REASSIGNED). A token
                // without the claim simply follows the database store.
                UUID legacyStoreId = jwtTokenProvider.extractLegacyStoreId(claims);
                if (legacyStoreId != null && !legacyStoreId.equals(employee.storeId())) {
                    writeError(response, "STORE_REASSIGNED");
                    return;
                }
                boolean profileRead = "GET".equals(request.getMethod())
                        && PROFILE_PATH.equals(request.getRequestURI());
                if (employee.passwordChangeRequired()
                        && !PASSWORD_CHANGE_ALLOWED.contains(request.getRequestURI()) && !profileRead) {
                    writeErrorWithStatus(response, "PASSWORD_CHANGE_REQUIRED", HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            }

            // Populate Spring Security context — authorities from the database role only.
            var auth = new UsernamePasswordAuthenticationToken(
                    userId, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            if ("EMPLOYEE".equals(role)) {
                auth.setDetails(new AuthDetails(employee.firstName(), employee.storeId(), sessionId));
            } else {
                // Owner: first name of its employee row when it has one (promoted employee).
                auth.setDetails(new AuthDetails(employee != null ? employee.firstName() : null, null, sessionId));
            }
            SecurityContextHolder.getContext().setAuthentication(auth);

            // Story 9-4 — DAU/MAU: authentication succeeded, record the user's activity of the day.
            recordActivity(userId, tenantId, role);

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            writeError(response, "TOKEN_EXPIRED");
        } catch (JwtException e) {
            writeError(response, "UNAUTHORIZED");
        } finally {
            // MANDATORY: clear ThreadLocal to prevent tenant leakage in thread-pool
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            MDC.remove(MDC_TENANT_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    private void recordActivity(UUID userId, String tenantSchema, String role) {
        try {
            userActivityRecorder.record(userId, tenantSchema, role);
        } catch (RuntimeException e) {
            // The recorder never throws; belt and braces — tracking must never affect the request.
            log.debug("User activity recording skipped ({})", e.getClass().getSimpleName());
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
