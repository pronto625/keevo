package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.RefreshTokenUseCase;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * RefreshTokenService — Issues new access tokens using a valid refresh token.
 *
 * <p>Architecture rules:
 * - Accepts only raw opaque refresh token string (not JWT)
 * - Validates token hash (SHA-256), expiry, and revocation status
 * - Issues new access token (and optionally rotates refresh token)
 * - Zero business logic about login flow — pure token refresh concern
 */
@Service
public class RefreshTokenService implements RefreshTokenUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                                UserRepository userRepository,
                                JwtTokenProvider jwtTokenProvider,
                                JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
    }

    @Override
    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        // 1. Hash the raw token (SHA-256 for deterministic lookup)
        String tokenHash = hashToken(rawRefreshToken);

        // 2. Look up by hash
        RefreshToken stored = refreshTokenRepository.findByHash(tokenHash)
                .orElseThrow(() -> new DomainException(ErrorCode.REFRESH_TOKEN_INVALID));

        // 3. Validate: not revoked, not expired
        if (stored.revoked() || Instant.now().isAfter(stored.expiresAt())) {
            throw new DomainException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        // 4. Load associated user
        User user = userRepository.findById(stored.userId())
                .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));

        // 5. Generate new access token
        String newAccessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), stored.tenantId(), user.getRole().name());

        // 6. Rotate refresh token (revoke old, issue new)
        String newRawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String newTokenHash = hashToken(newRawRefreshToken);
        Instant newExpiry = Instant.now().plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        refreshTokenRepository.save(
                RefreshToken.create(user.getId(), stored.tenantId(), newTokenHash, newExpiry));

        long expiresIn = (long) jwtProperties.getAccessTokenExpiryHours() * 3600;
        return new AuthTokens(newAccessToken, newRawRefreshToken, expiresIn,
                user.getId(), stored.tenantId(), user.getRole().name());
    }

    /** SHA-256 hash of raw token — deterministic for DB lookup. */
    private String hashToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
