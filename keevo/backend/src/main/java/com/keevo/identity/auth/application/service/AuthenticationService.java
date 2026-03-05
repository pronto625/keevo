package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserUseCase;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * AuthenticationService — Orchestrates user login and token issuance.
 *
 * <p>GoF Pattern: Façade — single entry point coordinating:
 * account lockout check → password verification → token generation →
 * refresh token persistence → audit event.
 *
 * <p>Architecture rules enforced:
 * - ActorId passed explicitly in command (never via SecurityContextHolder)
 * - No HTTP types in this class
 * - Password NEVER logged
 * - Zero business logic in AuthController — pure delegation here
 */
@Service
public class AuthenticationService implements AuthenticateUserUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final ApplicationEventPublisher eventPublisher;

    public AuthenticationService(UserRepository userRepository,
                                  TenantRepository tenantRepository,
                                  RefreshTokenRepository refreshTokenRepository,
                                  PasswordEncoder passwordEncoder,
                                  JwtTokenProvider jwtTokenProvider,
                                  JwtProperties jwtProperties,
                                  ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public AuthTokens authenticate(AuthenticateUserCommand command) {
        // 1. Load user — return INVALID_CREDENTIALS (not USER_NOT_FOUND) to avoid user enumeration
        User user = userRepository.findByPhoneNumber(command.phoneNumber())
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_CREDENTIALS));

        // 2. Check account lockout BEFORE verifying password
        if (isLocked(user)) {
            throw new DomainException(ErrorCode.ACCOUNT_LOCKED);
        }

        // 3. Verify password — increment counter on failure
        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            user = incrementFailedAttempts(user);
            userRepository.save(user);
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 4. Success — reset lockout state
        user = user.withLockoutState(0, null);
        userRepository.save(user);

        // 5. Look up tenant schema name for JWT routing
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        String schemaName = tenant.getSchemaName(); // e.g., "kv_abc123"

        // 6. Generate RS256 access token with schema name as tenantId claim
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), schemaName, user.getRole().name());

        // 7. Generate opaque refresh token and store its SHA-256 hash
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String tokenHash = hashToken(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        RefreshToken refreshToken = RefreshToken.create(user.getId(), schemaName, tokenHash, expiresAt);
        refreshTokenRepository.save(refreshToken);

        // 8. Publish audit event (Observer pattern)
        eventPublisher.publishEvent(new UserAuthenticatedEvent(
                user.getId(), schemaName, user.getRole().name(),
                null, // IP address not available at domain service level
                Instant.now()));

        long expiresIn = (long) jwtProperties.getAccessTokenExpiryHours() * 3600;
        return new AuthTokens(accessToken, rawRefreshToken, expiresIn,
                user.getId(), schemaName, user.getRole().name());
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private boolean isLocked(User user) {
        return user.lockedUntil() != null && Instant.now().isBefore(user.lockedUntil());
    }

    private User incrementFailedAttempts(User user) {
        int newCount = user.failedAttempts() + 1;
        Instant newLockedUntil = null;
        if (newCount >= MAX_FAILED_ATTEMPTS) {
            newLockedUntil = Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES);
            log.warn("Account locked for phone ending in ...{} until {}",
                    user.getPhoneNumber().length() >= 4
                            ? user.getPhoneNumber().substring(user.getPhoneNumber().length() - 4)
                            : "***",
                    newLockedUntil);
        }
        return user.withLockoutState(newCount, newLockedUntil);
    }

    /** SHA-256 hash of raw token — deterministic for DB lookup. */
    private String hashToken(String rawToken) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
