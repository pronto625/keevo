package com.keevo.shared.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * JwtTokenProvider — RS256 JWT token generation and validation.
 *
 * <p>GoF Pattern: Strategy — signing algorithm (RS256) is encapsulated here.
 * Swappable without modifying AuthenticationService.
 *
 * <p>Security constraints (NON-NEGOTIABLE):
 * <ul>
 *   <li>RS256 ONLY — asymmetric RSA 2048-bit key pair</li>
 *   <li>NEVER HS256 — symmetric keys can be brute-forced</li>
 *   <li>Refresh tokens are opaque random bytes, NOT JWTs</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    /** Story 12.12 — tolerated clock drift between issuer and verifier (exp/nbf/iat). */
    public static final long CLOCK_SKEW_SECONDS = 60;

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final JwtProperties jwtProperties;

    /** Spring-managed constructor. */
    public JwtTokenProvider(RSAPrivateKey rsaPrivateKey,
                            RSAPublicKey rsaPublicKey,
                            JwtProperties jwtProperties) {
        this.privateKey = rsaPrivateKey;
        this.publicKey = rsaPublicKey;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Story 16.11 — indicative V1 claims, written only while {@code keevo.jwt.legacy-claims} is on
     * (until Story 16.7 forces the minimum app version). Installed V1 apps read them for display;
     * <b>no server code ever reads them</b> (guard test {@code TokenClaimsGuardTest}) — role, store,
     * account status and the password-change flag come from the database on every request.
     */
    public record LegacyClaims(String role, String tenantStatus, UUID storeId,
                               boolean passwordChangeRequired, Boolean onboardingCompleted) {
    }

    /**
     * Story 16.11 — access token proving identity only: {@code sub}, {@code tenantId}, {@code sid}
     * (device session), {@code iat}, {@code exp}. Lifetime {@code keevo.jwt.access-token-expiry-minutes}.
     *
     * @param userId    subject (user UUID)
     * @param tenantId  tenant schema name (e.g. {@code kv_abc123}, {@code public} for the super-admin)
     * @param sessionId device session id ({@code refresh_tokens.session_id}); omitted when null
     * @param legacy    indicative V1 claims, written only when the legacy flag is on; may be null
     * @return compact JWT string
     */
    public String generateAccessToken(UUID userId, String tenantId, UUID sessionId, LegacyClaims legacy) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getAccessTokenExpiryMinutes(), ChronoUnit.MINUTES)))
                .signWith(privateKey, Jwts.SIG.RS256);
        if (sessionId != null) {
            builder.claim("sid", sessionId.toString());
        }
        if (legacy != null && jwtProperties.isLegacyClaims()) {
            if (legacy.role() != null) builder.claim("role", legacy.role());
            if (legacy.tenantStatus() != null) builder.claim("tenantStatus", legacy.tenantStatus());
            if (legacy.storeId() != null) builder.claim("storeId", legacy.storeId().toString());
            if (legacy.passwordChangeRequired()) builder.claim("passwordChangeRequired", true);
            if (legacy.onboardingCompleted() != null) {
                builder.claim("onboardingCompleted", legacy.onboardingCompleted());
            }
        }
        return builder.compact();
    }

    /** Story 16.11 — configured access-token lifetime. */
    public java.time.Duration accessTokenLifetime() {
        return java.time.Duration.ofMinutes(jwtProperties.getAccessTokenExpiryMinutes());
    }

    /**
     * Session-less access token (tests and tooling). The role is only an indicative legacy claim:
     * the server reads the real role from the database.
     */
    public String generateAccessToken(UUID userId, String tenantId, String role) {
        return generateAccessToken(userId, tenantId, null, new LegacyClaims(role, "ACTIVE", null, false, null));
    }

    /** Session-less access token with legacy store / password claims (tests and tooling). */
    public String generateAccessToken(UUID userId, String tenantId, String role,
                                       String tenantStatus, UUID storeId,
                                       boolean passwordChangeRequired) {
        return generateAccessToken(userId, tenantId, null,
                new LegacyClaims(role, tenantStatus, storeId, passwordChangeRequired, null));
    }

    /**
     * Generate a short-lived RS256 JWT login token (Story 1.7 two-step login).
     *
     * <p>This token has a 5-minute TTL and carries {@code scope = "login_pending"}.
     * It is NOT usable as an access token — {@link JwtAuthFilter} MUST reject it.
     *
     * @param userId the authenticated user's UUID (becomes JWT subject)
     * @return compact JWT string (TTL: 5 min, scope: "login_pending")
     */
    public String generateLoginToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("scope", "login_pending")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Extract the {@code scope} claim from parsed JWT claims.
     *
     * <p>Returns {@code null} if the claim is absent (standard access tokens have no scope).
     * Returns {@code "login_pending"} for login tokens.
     *
     * @param claims pre-parsed token claims
     * @return scope string or null
     */
    public String extractScope(Claims claims) {
        return claims.get("scope", String.class);
    }

    /**
     * Generate a cryptographically random opaque refresh token.
     * 64 bytes of SecureRandom, base64url-encoded (no padding).
     *
     * @return URL-safe base64 string (no ', /, = characters)
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Parse and validate a JWT token, returning its claims.
     *
     * @param token compact JWT string
     * @return validated claims
     * @throws ExpiredJwtException if the token has expired
     * @throws io.jsonwebtoken.JwtException if the token is invalid or malformed
     */
    public Claims parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // Story 12.12: a token "issued" in the future (beyond the allowed skew) is invalid —
        // it would otherwise survive a later revocation cutoff (iat >= tokens_valid_after).
        Date iat = claims.getIssuedAt();
        if (iat != null && iat.toInstant().isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
            throw new FutureIssuedAtException("JWT issued-at is in the future beyond allowed clock skew");
        }
        return claims;
    }

    /** Raised when a token's {@code iat} is later than now + {@link #CLOCK_SKEW_SECONDS}. */
    public static class FutureIssuedAtException extends JwtException {
        public FutureIssuedAtException(String message) {
            super(message);
        }
    }

    /** Extract userId (subject) from parsed claims. */
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /** Extract tenantId from parsed claims. */
    public String extractTenantId(Claims claims) {
        return claims.get("tenantId", String.class);
    }

    /**
     * Story 16.11 — device session id ({@code sid}), or {@code null} for a token issued before
     * device sessions existed (or malformed).
     */
    public UUID extractSessionId(Claims claims) {
        String sid = claims.get("sid", String.class);
        if (sid == null) return null;
        try {
            return UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Story 16.11 — compatibility only: the legacy {@code storeId} claim of an installed V1 app's
     * token, used by {@link JwtAuthFilter} solely to answer {@code STORE_REASSIGNED} once so the
     * old app renews. Never an authorization input (the store always comes from the database).
     */
    UUID extractLegacyStoreId(Claims claims) {
        String storeId = claims.get("storeId", String.class);
        if (storeId == null) return null;
        try {
            return UUID.fromString(storeId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Extract the {@code iat} (issued-at) claim as an Instant. Returns {@code null} if absent
     * (backward-compatible with very old tokens). Used by JwtAuthFilter for session revocation
     * cutoff (Story 12.2): {@code iat < tokens_valid_after → 401 SESSION_REVOKED}.
     */
    public Instant extractIssuedAt(Claims claims) {
        Date issuedAt = claims.getIssuedAt();
        return issuedAt != null ? issuedAt.toInstant() : null;
    }

    /**
     * Check if a token is expired (without throwing an exception).
     *
     * @return true if expired, false if valid
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return !claims.getExpiration().after(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

}
