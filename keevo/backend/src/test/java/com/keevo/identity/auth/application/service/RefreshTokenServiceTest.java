package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RefreshTokenServiceTest — TDD RED tests for token refresh logic.
 *
 * <p>Written BEFORE the implementation (Story 1.3 TDD requirement).
 *
 * <p>Test Validity: The mock for {@code refreshTokenRepository.findByHash()} is set up
 * with the EXACT expected SHA-256 hash of the raw token — not {@code anyString()}.
 * This ensures the test fails if the production code omits or miscalculates the hash.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock UserRepository userRepository;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock com.keevo.shared.infrastructure.security.JwtProperties jwtProperties;
    @InjectMocks RefreshTokenService refreshTokenService;

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh with valid token computes SHA-256 hash and returns new AuthTokens")
    void should_return_new_tokens_for_valid_refresh_token() {
        // Arrange: compute the hash the production code MUST produce
        String rawToken = "raw-refresh-token";
        String expectedHash = sha256Base64Url(rawToken);

        UUID userId = UUID.randomUUID();
        RefreshToken storedToken = RefreshToken.create(userId, "KV-ABC123",
                expectedHash, Instant.now().plus(30, ChronoUnit.DAYS));
        User user = buildUser(userId, "+22670000001", "OWNER");

        // Use exact hash — if production code doesn't hash correctly, Optional.empty() is returned
        when(refreshTokenRepository.findByHash(expectedHash)).thenReturn(Optional.of(storedToken));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("new-access-tok");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("new-refresh-tok");
        when(jwtProperties.getRefreshTokenExpiryDays()).thenReturn(30);
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        AuthTokens result = refreshTokenService.refresh(rawToken);

        // Assert
        assertThat(result.accessToken()).isEqualTo("new-access-tok");
        assertThat(result.refreshToken()).isEqualTo("new-refresh-tok");
        // Verify findByHash was called with the correct SHA-256 hash (not the raw token)
        verify(refreshTokenRepository).findByHash(expectedHash);
        verify(refreshTokenRepository, never()).findByHash(rawToken); // raw token MUST NOT be passed
    }

    @Test
    @DisplayName("refresh rotates refresh token — old hash replaced by new hash in DB")
    void should_save_new_refresh_token_on_rotation() {
        String rawToken = "raw-token-to-rotate";
        String expectedHash = sha256Base64Url(rawToken);
        UUID userId = UUID.randomUUID();
        RefreshToken storedToken = RefreshToken.create(userId, "kv_abc123",
                expectedHash, Instant.now().plus(30, ChronoUnit.DAYS));
        User user = buildUser(userId, "+22670000001", "OWNER");

        when(refreshTokenRepository.findByHash(expectedHash)).thenReturn(Optional.of(storedToken));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("new-raw-token");
        when(jwtProperties.getRefreshTokenExpiryDays()).thenReturn(30);
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        refreshTokenService.refresh(rawToken);

        // A NEW refresh token must be saved (rotation) — verify save was called with a RefreshToken
        verify(refreshTokenRepository).save(argThat(rt -> rt.tokenHash() != null
                && !rt.tokenHash().equals(expectedHash))); // new hash, not the original
    }

    // ── Invalid/expired/revoked token ─────────────────────────────────────

    @Test
    @DisplayName("refresh with unknown token throws REFRESH_TOKEN_INVALID")
    void should_throw_refresh_token_invalid_for_unknown_token() {
        String rawToken = "unknown-token";
        String expectedHash = sha256Base64Url(rawToken);
        // Exact hash returns empty — simulates real DB miss
        when(refreshTokenRepository.findByHash(expectedHash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.refresh(rawToken))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));
    }

    @Test
    @DisplayName("refresh with expired token throws REFRESH_TOKEN_INVALID")
    void should_throw_refresh_token_invalid_for_expired_token() {
        String rawToken = "expired-raw";
        String expectedHash = sha256Base64Url(rawToken);
        UUID userId = UUID.randomUUID();
        RefreshToken expiredToken = RefreshToken.create(userId, "KV-ABC123",
                expectedHash, Instant.now().minus(1, ChronoUnit.DAYS)); // expired

        when(refreshTokenRepository.findByHash(expectedHash)).thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> refreshTokenService.refresh(rawToken))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));
    }

    @Test
    @DisplayName("refresh with revoked token throws REFRESH_TOKEN_INVALID")
    void should_throw_refresh_token_invalid_for_revoked_token() {
        String rawToken = "revoked-raw";
        String expectedHash = sha256Base64Url(rawToken);
        UUID userId = UUID.randomUUID();
        RefreshToken revokedToken = new RefreshToken(UUID.randomUUID(), userId,
                "KV-ABC123", expectedHash,
                Instant.now().plus(30, ChronoUnit.DAYS), true); // revoked = true

        when(refreshTokenRepository.findByHash(expectedHash)).thenReturn(Optional.of(revokedToken));

        assertThatThrownBy(() -> refreshTokenService.refresh(rawToken))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Mirrors the production hash function in {@code RefreshTokenService.hashToken()}.
     * If the production implementation changes its hashing strategy, this test will
     * catch the divergence — the mock won't match and the test will fail.
     */
    private static String sha256Base64Url(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private User buildUser(UUID id, String phone, String role) {
        return new User(id, phone, "$2a$12$hash",
                Role.valueOf(role),
                true, Instant.now(), 0, null);
    }
}


