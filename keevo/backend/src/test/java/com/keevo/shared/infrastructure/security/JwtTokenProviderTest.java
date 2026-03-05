package com.keevo.shared.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * JwtTokenProviderTest — TDD RED tests for JWT RS256 token generation and validation.
 *
 * <p>Written BEFORE the implementation (Story 1.3 TDD requirement).
 */
@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private JwtTokenProvider provider;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        privateKey = loadTestPrivateKey();
        publicKey = loadTestPublicKey();
        JwtProperties props = new JwtProperties(24, 30);
        provider = new JwtTokenProvider(privateKey, publicKey, props);
    }

    // ── Access token generation ────────────────────────────────────────────

    @Test
    @DisplayName("generateAccessToken produces valid RS256 JWT with correct claims")
    void should_generate_valid_rs256_jwt_with_correct_claims() {
        UUID userId = UUID.randomUUID();
        String tenantId = "KV-ABC123";
        String role = "OWNER";

        String token = provider.generateAccessToken(userId, tenantId, role);

        Claims claims = provider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tenantId", String.class)).isEqualTo(tenantId);
        assertThat(claims.get("role", String.class)).isEqualTo(role);
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    @DisplayName("extractUserId returns correct UUID from claims")
    void should_extract_user_id_from_claims() {
        UUID userId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId, "KV-TEST", "OWNER");

        Claims claims = provider.parseToken(token);
        assertThat(provider.extractUserId(claims)).isEqualTo(userId);
    }

    @Test
    @DisplayName("extractTenantId returns correct tenantId from claims")
    void should_extract_tenant_id_from_claims() {
        String tenantId = "KV-XYZ789";
        String token = provider.generateAccessToken(UUID.randomUUID(), tenantId, "EMPLOYEE");

        Claims claims = provider.parseToken(token);
        assertThat(provider.extractTenantId(claims)).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("extractRole returns correct role from claims")
    void should_extract_role_from_claims() {
        String role = "EMPLOYEE";
        String token = provider.generateAccessToken(UUID.randomUUID(), "KV-ABC", role);

        Claims claims = provider.parseToken(token);
        assertThat(provider.extractRole(claims)).isEqualTo(role);
    }

    // ── Token expiry ───────────────────────────────────────────────────────

    @Test
    @DisplayName("parseToken throws ExpiredJwtException for expired token")
    void should_throw_expired_exception_for_expired_token() {
        // Use a provider configured with 0-hour expiry
        JwtProperties expiredProps = new JwtProperties(0, 30);
        JwtTokenProvider expiredProvider = new JwtTokenProvider(privateKey, publicKey, expiredProps);

        String token = expiredProvider.generateAccessToken(UUID.randomUUID(), "KV-T", "OWNER");

        // Token with 0-hour expiry should fail immediately (past or exactly now)
        // Use the standard provider to parse — it will see the token as expired
        assertThatThrownBy(() -> provider.parseToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("isTokenExpired returns true for expired token")
    void should_return_true_for_expired_token() {
        JwtProperties expiredProps = new JwtProperties(0, 30);
        JwtTokenProvider expiredProvider = new JwtTokenProvider(privateKey, publicKey, expiredProps);

        String token = expiredProvider.generateAccessToken(UUID.randomUUID(), "KV-T", "OWNER");

        assertThat(provider.isTokenExpired(token)).isTrue();
    }

    @Test
    @DisplayName("isTokenExpired returns false for valid token")
    void should_return_false_for_valid_token() {
        String token = provider.generateAccessToken(UUID.randomUUID(), "KV-T", "OWNER");

        assertThat(provider.isTokenExpired(token)).isFalse();
    }

    // ── Refresh token ──────────────────────────────────────────────────────

    @Test
    @DisplayName("generateRefreshToken produces opaque base64url token of sufficient length")
    void should_generate_opaque_refresh_token_of_sufficient_length() {
        String refreshToken = provider.generateRefreshToken();

        // Base64url of 64 bytes ≈ 86 characters
        assertThat(refreshToken).hasSizeGreaterThanOrEqualTo(80);
        // Must be URL-safe base64 (no +, /, =)
        assertThat(refreshToken).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("generateRefreshToken produces unique tokens on each call")
    void should_generate_unique_refresh_tokens() {
        String t1 = provider.generateRefreshToken();
        String t2 = provider.generateRefreshToken();

        assertThat(t1).isNotEqualTo(t2);
    }

    // ── Test helpers ───────────────────────────────────────────────────────

    private RSAPrivateKey loadTestPrivateKey() throws Exception {
        var stream = getClass().getResourceAsStream("/keys/private_key.pem");
        assertThat(stream).as("Test private key must exist at src/test/resources/keys/private_key.pem").isNotNull();
        String pem = new String(stream.readAllBytes())
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private RSAPublicKey loadTestPublicKey() throws Exception {
        var stream = getClass().getResourceAsStream("/keys/public_key.pem");
        assertThat(stream).as("Test public key must exist at src/test/resources/keys/public_key.pem").isNotNull();
        String pem = new String(stream.readAllBytes())
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decoded));
    }
}
