package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.Role;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.ErrorMessages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Story 19.5 — a VIEWER never writes (Intercepting Filter, one place instead of every endpoint): after
 * {@link JwtAuthFilter} has authenticated the request, any method other than {@code GET}/{@code HEAD}/{@code OPTIONS}
 * from a {@code ROLE_VIEWER} is answered 403 {@code FORBIDDEN} (the shared French message, same body as the access
 * denied handler), unless method and path (within the application) are in the self-service
 * {@link #ALLOWED_WRITES allow-list}. Sync push is allowed
 * here and refused operation by operation ({@code REJECTED/FORBIDDEN}) by the push service.
 *
 * <p>Registered in {@link SecurityConfig} right after {@link JwtAuthFilter} (not a bean: never a second servlet
 * filter). Cost O(1) per request: a method check, then — for a VIEWER write only — a match against a constant list of 19.
 */
public class ViewerWriteGuardFilter extends OncePerRequestFilter {

    /**
     * One self-service write a VIEWER may still make: an exact HTTP method and an exact path pattern
     * ({@code {var}} segments only, no subtree wildcard — a write added later under the same prefix stays blocked).
     */
    public record AllowedWrite(String method, String pattern) {
    }

    /**
     * The writes a VIEWER may still make — its own session, devices, notifications, feedback, sync plumbing and
     * assistant (21.4). Every
     * entry is an endpoint that exists; the set is pinned against the scanned handlers by
     * {@code ViewerAllowListPinTest}. (Public auth endpoints carry no authentication and never reach this rule.)
     */
    public static final List<AllowedWrite> ALLOWED_WRITES = List.of(
            new AllowedWrite("POST", "/api/v1/auth/change-password"),
            new AllowedWrite("DELETE", "/api/v1/auth/sessions"),
            new AllowedWrite("DELETE", "/api/v1/auth/sessions/{sessionId}"),
            // Story 19.3b — the viewer's own Google / Apple links
            new AllowedWrite("POST", "/api/v1/auth/social/accounts/google"),
            new AllowedWrite("POST", "/api/v1/auth/social/accounts/apple"),
            new AllowedWrite("DELETE", "/api/v1/auth/social/accounts/{provider}"),
            new AllowedWrite("POST", "/api/v1/devices/token"),
            new AllowedWrite("DELETE", "/api/v1/devices/token"),
            new AllowedWrite("POST", "/api/v1/web-push/subscribe"),
            new AllowedWrite("DELETE", "/api/v1/web-push/subscribe"),
            new AllowedWrite("PATCH", "/api/v1/notifications/read-all"),
            new AllowedWrite("PATCH", "/api/v1/notifications/{id}/read"),
            new AllowedWrite("POST", "/api/v1/feedback"),
            new AllowedWrite("POST", "/api/v1/sync/push"),
            // Story 21.4 — the assistant: asking a question only reads (every tool is read-only and keeps its own
            // gates); the conversations saved are the viewer's own
            new AllowedWrite("POST", "/api/v1/ai/chat"),
            new AllowedWrite("POST", "/api/v1/ai/conversations"),
            new AllowedWrite("PUT", "/api/v1/ai/conversations/{id}"),
            new AllowedWrite("DELETE", "/api/v1/ai/conversations/{id}"),
            new AllowedWrite("DELETE", "/api/v1/ai/conversations/batch"));

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String VIEWER_AUTHORITY = "ROLE_" + Role.VIEWER.name();
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final ObjectMapper objectMapper;

    public ViewerWriteGuardFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (READ_METHODS.contains(request.getMethod()) || !isViewer()
                || isAllowed(request.getMethod(), PATH_HELPER.getPathWithinApplication(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponseWrapper.error(
                ErrorMessages.of("FORBIDDEN", "Vous n'avez pas les droits nécessaires"),
                HttpStatus.FORBIDDEN.name(), "FORBIDDEN", null));
    }

    /** Whether {@code method} on {@code path} (within the application) is a self-service write a VIEWER may make. */
    public static boolean isAllowed(String method, String path) {
        return method != null && path != null && ALLOWED_WRITES.stream()
                .anyMatch(a -> a.method().equals(method) && PATH_MATCHER.match(a.pattern(), path));
    }

    private static boolean isViewer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> VIEWER_AUTHORITY.equals(a.getAuthority()));
    }
}
