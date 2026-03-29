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

    // ── Login token (Story 1.7) ──────────────────────────────────────────────────

    @Test
    @DisplayName("generateLoginToken produces RS256 JWT with scope=login_pending and 5min TTL")
    void should_generate_login_token_with_login_pending_scope() {
        UUID userId = UUID.randomUUID();

        String loginToken = provider.generateLoginToken(userId);

        Claims claims = provider.parseToken(loginToken);
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("scope", String.class)).isEqualTo("login_pending");
        // 5 min TTL: expiration should be roughly between now+4min and now+6min
        long expiresInMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        assertThat(expiresInMs).isBetween(4 * 60 * 1000L, 6 * 60 * 1000L);
    }

    @Test
    @DisplayName("generateLoginToken does NOT embed tenantId or role claims")
    void should_generate_login_token_without_tenant_or_role_claims() {
        UUID userId = UUID.randomUUID();

        String loginToken = provider.generateLoginToken(userId);

        Claims claims = provider.parseToken(loginToken);
        assertThat(claims.get("tenantId", String.class)).isNull();
        assertThat(claims.get("role", String.class)).isNull();
    }

    @Test
    @DisplayName("extractScope returns login_pending for a loginToken")
    void should_extract_login_pending_scope_from_login_token() {
        UUID userId = UUID.randomUUID();
        String loginToken = provider.generateLoginToken(userId);

        Claims claims = provider.parseToken(loginToken);
        assertThat(provider.extractScope(claims)).isEqualTo("login_pending");
    }

    @Test
    @DisplayName("extractScope returns null for a regular accessToken (no scope claim)")
    void should_return_null_scope_for_access_token() {
        String accessToken = provider.generateAccessToken(UUID.randomUUID(), "kv_abc123", "OWNER");

        Claims claims = provider.parseToken(accessToken);
        assertThat(provider.extractScope(claims)).isNull();
    }

    // ── firstName claim (Story 7.1) ──────────────────────────────────────────

    @Test
    @DisplayName("generateAccessToken includes firstName claim when provided")
    void should_include_firstName_claim_when_provided() {
        UUID userId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId, "kv_abc123", "EMPLOYEE",
                "ACTIVE", "Simon");

        Claims claims = provider.parseToken(token);
        assertThat(provider.extractFirstName(claims)).isEqualTo("Simon");
    }

    @Test
    @DisplayName("generateAccessToken omits firstName claim when null")
    void should_omit_firstName_claim_when_null() {
        String token = provider.generateAccessToken(UUID.randomUUID(), "kv_abc123", "OWNER");

        Claims claims = provider.parseToken(token);
        assertThat(provider.extractFirstName(claims)).isNull();
    }

    @Test
    @DisplayName("7-arg generateAccessToken includes firstName claim for EMPLOYEE")
    void should_include_firstName_in_7arg_overload() {
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId, "kv_abc123", "EMPLOYEE",
                "ACTIVE", storeId, true, "Loïc");

        Claims claims = provider.parseToken(token);
        assertThat(provider.extractFirstName(claims)).isEqualTo("Loïc");
        assertThat(claims.get("storeId", String.class)).isEqualTo(storeId.toString());
        assertThat(claims.get("passwordChangeRequired", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("existing endpoints still work — 3-arg overload backward compatible")
    void should_remain_backward_compatible_3arg() {
        UUID userId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId, "kv_test", "OWNER");

        Claims claims = provider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tenantId", String.class)).isEqualTo("kv_test");
        assertThat(claims.get("role", String.class)).isEqualTo("OWNER");
        assertThat(provider.extractFirstName(claims)).isNull();
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
