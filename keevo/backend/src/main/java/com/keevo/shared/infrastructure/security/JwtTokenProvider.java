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
     * Generate a signed RS256 JWT access token.
     *
     * @param userId   subject (user UUID)
     * @param tenantId tenant code (e.g., "KV-ABC123")
     * @param role     user role
     * @return compact JWT string
     */
    public String generateAccessToken(UUID userId, String tenantId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getAccessTokenExpiryHours(), ChronoUnit.HOURS)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
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

    // ── Legacy methods (backward compatibility with Story 1.2 stub callers) ──────

    /**
     * @deprecated Use {@link #generateAccessToken(UUID, String, String)} instead.
     *             Kept for backward compatibility — will be removed in Story 1.4.
     */
    @Deprecated(since = "1.3", forRemoval = true)
    public String generateToken(String subject, String tenantId) {
        return generateAccessToken(UUID.fromString(subject), tenantId, "OWNER");
    }

    /**
     * @deprecated Use {@link #parseToken(String)} instead.
     */
    @Deprecated(since = "1.3", forRemoval = true)
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @deprecated Use {@link #extractUserId(Claims)} instead.
     */
    @Deprecated(since = "1.3", forRemoval = true)
    public String getSubject(String token) {
        try {
            return parseToken(token).getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @deprecated Use {@link #extractTenantId(Claims)} instead.
     */
    @Deprecated(since = "1.3", forRemoval = true)
    public String getTenantId(String token) {
        try {
            Claims claims = parseToken(token);
            return extractTenantId(claims);
        } catch (Exception e) {
            return null;
        }
    }
}
