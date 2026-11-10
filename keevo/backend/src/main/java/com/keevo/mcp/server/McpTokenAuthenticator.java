package com.keevo.mcp.server;

import com.keevo.mcp.module.McpAccessModule;
import com.keevo.mcp.token.domain.model.McpTokenSecrets;
import com.keevo.mcp.token.domain.port.out.McpAccessTokenRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CredentialAuthenticator;
import com.keevo.shared.infrastructure.web.ClientIps;
import com.keevo.shared.module.EntitlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * Story 21.9 — the personal access tokens of the MCP server as a {@link CredentialAuthenticator}: the shared
 * {@code CredentialAuthFilter} binds the token owner's session (same checks and shape as the JWT path) for every
 * request to {@link McpServerConfig#ENDPOINT}.
 *
 * <p>{@link #resolve}: {@code Authorization: Bearer kvp_…} shape checked without I/O, then one lookup by SHA-256
 * (unique index), constant-time hash comparison, expiry; a failure is counted per IP and an IP that failed too often
 * this minute gets 429 (a valid token is never refused because of its IP). Never logs the token.
 * {@link #afterAuthentication} (session bound): module {@code mcp-access} entitled and enabled (403
 * {@code MODULE_NOT_ENTITLED} / {@code MODULE_DISABLED_BY_OWNER}, nothing served), then rate limit per token and
 * company (429, {@code Retry-After}), last use recorded at most once a minute per token. A revoked token is a deleted row: refused at the next request on every instance.
 */
@Component
public class McpTokenAuthenticator implements CredentialAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(McpTokenAuthenticator.class);

    static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
    static final int RETRY_AFTER_SECONDS = 60;
    private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

    private final McpAccessTokenRepository tokens;
    private final McpRateLimiter limiter;
    private final EntitlementService entitlements;
    private final Clock clock;

    public McpTokenAuthenticator(McpAccessTokenRepository tokens, McpRateLimiter limiter,
                                 EntitlementService entitlements, Clock clock) {
        this.tokens = tokens;
        this.limiter = limiter;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    /**
     * The decoded path within the application (servlet path + path info): what the router matches, so an encoded
     * ({@code /api/v1/%6Dcp}) or context-prefixed request can never reach the MCP route without this credential, and
     * a trailing slash (not routed) is not authenticated for nothing (21.4.2 review).
     */
    @Override
    public boolean handles(HttpServletRequest request) {
        return McpServerConfig.ENDPOINT.equals(PATHS.getPathWithinApplication(request));
    }

    @Override
    public String revokedCode() {
        return ErrorCode.MCP_TOKEN_INVALID.name();
    }

    /**
     * The token first: a valid token is never refused because of its IP — MCP clients of one AI provider, or a mobile
     * carrier's NAT, share their address, and one bad client must not lock every company out (21.4.2 review). Only a
     * failed authentication is braked per IP (429 once the IP failed too often this minute).
     */
    @Override
    public Resolution resolve(HttpServletRequest request) {
        Resolution resolution = lookup(request);
        if (resolution instanceof Refusal refusal && refusal.status() == HttpServletResponse.SC_UNAUTHORIZED) {
            String ip = ClientIps.of(request);
            if (limiter.tooManyFailures(ip)) {
                return tooMany();
            }
            limiter.recordFailure(ip);
        }
        return resolution;
    }

    private Resolution lookup(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).strip() : null;
        if (!McpTokenSecrets.looksLikeToken(token)) {
            return invalid();
        }
        byte[] hash = McpTokenSecrets.hash(token);
        Optional<McpAccessTokenRepository.Credential> found;
        try {
            found = tokens.findByHash(hash);
        } catch (DataAccessException e) {
            log.error("MCP token lookup failed ({})", e.getClass().getSimpleName());
            return Refusal.of(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        }
        if (found.isEmpty() || !McpTokenSecrets.sameHash(hash, found.get().tokenHash())) {
            return invalid();
        }
        McpAccessTokenRepository.Credential credential = found.get();
        if (!credential.expiresAt().isAfter(clock.instant())) {
            return invalid();
        }
        request.setAttribute(McpRequestSession.ATTRIBUTE, new McpRequestSession(credential.id(),
                new HashSet<>(credential.tools()), credential.lastUsedAt()));
        return new Owner(credential.userId(), credential.schema(), credential.createdAt());
    }

    @Override
    public Refusal afterAuthentication(HttpServletRequest request, Owner owner) {
        McpRequestSession session = (McpRequestSession) request.getAttribute(McpRequestSession.ATTRIBUTE);
        // the module first: a refused company's retries never use up its rate windows
        try {
            entitlements.assertEnabled(owner.schema(), McpAccessModule.ID);
        } catch (DomainException e) {
            String code = e.getDomainCode() != null ? e.getDomainCode() : ErrorCode.MODULE_NOT_ENTITLED.name();
            return Refusal.of(HttpServletResponse.SC_FORBIDDEN, code);
        }
        if (!limiter.tryAcquire(session.tokenId(), owner.schema())) {
            return tooMany();
        }
        touch(session);
        return null;
    }

    /** Records the use at most once a minute per token; a failure never refuses the request. */
    private void touch(McpRequestSession session) {
        Instant now = clock.instant();
        if (session.lastUsedAt() != null && session.lastUsedAt().plus(TOUCH_EVERY).isAfter(now)) {
            return;
        }
        try {
            tokens.touch(session.tokenId(), now, now.minus(TOUCH_EVERY));
        } catch (DataAccessException e) {
            log.debug("MCP token last-use update skipped ({})", e.getClass().getSimpleName());
        }
    }

    private static Refusal tooMany() {
        return new Refusal(429, ErrorCode.RATE_LIMIT_EXCEEDED.name(), null,
                Map.of("Retry-After", String.valueOf(RETRY_AFTER_SECONDS)));
    }

    private static Refusal invalid() {
        return new Refusal(HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.MCP_TOKEN_INVALID.name(), null,
                Map.of("WWW-Authenticate", "Bearer"));
    }
}
