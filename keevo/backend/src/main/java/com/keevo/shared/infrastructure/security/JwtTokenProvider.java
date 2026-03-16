package com.keevo.shared.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
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
     * Generate a signed RS256 JWT access token with tenant status claim.
     *
     * @param userId       subject (user UUID)
     * @param tenantId     tenant schema name (e.g., "kv_abc123")
     * @param role         user role
     * @param tenantStatus tenant status ("ACTIVE" | "SUSPENDED") — embedded for
     *                     JwtAuthFilter suspension check without extra DB round-trip
     * @return compact JWT string
     */
    public String generateAccessToken(UUID userId, String tenantId, String role, String tenantStatus) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .claim("role", role)
                .claim("tenantStatus", tenantStatus)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getAccessTokenExpiryHours(), ChronoUnit.HOURS)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Generate a signed RS256 JWT access token (defaults tenantStatus to "ACTIVE").
     *
     * @param userId   subject (user UUID)
     * @param tenantId tenant schema name (e.g., "kv_abc123")
     * @param role     user role
     * @return compact JWT string
     */
    public String generateAccessToken(UUID userId, String tenantId, String role) {
        return generateAccessToken(userId, tenantId, role, "ACTIVE");
    }

    /**
     * Generate a signed RS256 JWT with storeId and passwordChangeRequired claims.
     * Story 3.5 — EMPLOYEE tokens include store assignment and forced-change flag.
     *
     * @param userId                  subject (user UUID)
     * @param tenantId                tenant schema name
     * @param role                    user role
     * @param tenantStatus            tenant status
     * @param storeId                 assigned store UUID (null for OWNER)
     * @param passwordChangeRequired  true if employee must change password
     * @return compact JWT string
     */
    public String generateAccessToken(UUID userId, String tenantId, String role,
                                       String tenantStatus, UUID storeId,
                                       boolean passwordChangeRequired) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .claim("role", role)
                .claim("tenantStatus", tenantStatus)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getAccessTokenExpiryHours(), ChronoUnit.HOURS)))
                .signWith(privateKey, Jwts.SIG.RS256);
        if (storeId != null) {
            builder.claim("storeId", storeId.toString());
        }
        if (passwordChangeRequired) {
            builder.claim("passwordChangeRequired", true);
        }
        return builder.compact();
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
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Extract userId (subject) from parsed claims. */
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /** Extract tenantId from parsed claims. */
    public String extractTenantId(Claims claims) {
        return claims.get("tenantId", String.class);
    }

    /** Extract role from parsed claims. */
    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }

    /**
     * Extract tenantStatus from parsed claims.
     * Defaults to "ACTIVE" if the claim is absent (backward-compatible with old tokens).
     */
    public String extractTenantStatus(Claims claims) {
        String status = claims.get("tenantStatus", String.class);
        return status != null ? status : "ACTIVE";
    }

    /** Extract storeId from parsed claims. Returns null if absent (OWNER tokens). */
    public UUID extractStoreId(Claims claims) {
        String storeId = claims.get("storeId", String.class);
        return storeId != null ? UUID.fromString(storeId) : null;
    }

    /** Extract passwordChangeRequired from parsed claims. Returns false if absent. */
    public boolean extractPasswordChangeRequired(Claims claims) {
        Boolean val = claims.get("passwordChangeRequired", Boolean.class);
        return val != null && val;
    }

    /**
     * Check if a token is expired (without throwing an exception).
     *
     * @return true if expired, false if valid
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

}
