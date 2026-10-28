package com.keevo.shared.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * PrometheusScrapeAuthFilter — static bearer-token guard for {@code /actuator/prometheus}
 * (Story 11.2, MOD-NFR4).
 *
 * <p>Prod is publicly routed by Traefik, so the scrape endpoint is never anonymous:
 * <ul>
 *   <li>token unset or blank ({@code KEEVO_PROMETHEUS_TOKEN}) → 404, the endpoint does not exist;</li>
 *   <li>missing or wrong {@code Authorization: Bearer <token>} → 401, no body,
 *       {@code WWW-Authenticate: Bearer} (RFC 6750);</li>
 *   <li>correct token → the request continues to the actuator endpoint.</li>
 * </ul>
 * The comparison is constant-time ({@link MessageDigest#isEqual}). The path is in
 * {@code SecurityConfig.PUBLIC_PATHS}, so {@code JwtAuthFilter} never parses the scrape token as a JWT;
 * this filter is the only protection and runs inside the security chain before authorization.
 *
 * <p>Not a {@code @Component}: built by {@code SecurityConfig} so the servlet container does not
 * register it a second time outside the security chain.
 */
public class PrometheusScrapeAuthFilter extends OncePerRequestFilter {

    static final String PROMETHEUS_PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;

    public PrometheusScrapeAuthFilter(String token) {
        this.expectedToken = token == null || token.isBlank()
                ? null
                : token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        // a trailing slash must not slip around the guard
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return !PROMETHEUS_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (expectedToken == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!matches(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); // RFC 6750
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        byte[] supplied = authorization.substring(BEARER_PREFIX.length()).strip()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, supplied);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) {
            return "";
        }
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }
}
