package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
            "/api/v1/auth/refresh"
    );

    /** Ant-style paths (wildcards) that are also public. */
    private static final List<String> PUBLIC_ANT_PATHS = List.of(
            "/v3/api-docs/**",
            "/swagger-ui/**"
    );

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider, ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
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
            String tenantId = jwtTokenProvider.extractTenantId(claims);
            String role = jwtTokenProvider.extractRole(claims);
            UUID userId = jwtTokenProvider.extractUserId(claims);

            // Set multi-tenant context for JPA routing
            TenantContext.setCurrentTenant(tenantId);

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

    private void writeError(HttpServletResponse response, String domainCode) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = objectMapper.writeValueAsString(Map.of("domainCode", domainCode));
        response.getWriter().write(body);
    }
}
