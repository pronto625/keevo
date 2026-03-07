package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserUseCase;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * AuthenticationService — Orchestrates user login step 1 (two-step login, Story 1.7).
 *
 * <p>GoF Pattern: Façade — coordinates:
 * account lockout check → password verification → membership loading → loginToken generation.
 *
 * <p>Step 1 ONLY — does NOT issue access/refresh tokens (moved to {@link SelectTenantService}).
 * Step 1 returns a short-lived {@code loginToken} (5min) + the user's tenant memberships.
 *
 * <p>Architecture rules enforced:
 * - NEVER selects tenant here — this service knows only global identity
 * - No HTTP types in this class
 * - Password NEVER logged
 */
@Service
public class AuthenticationService implements AuthenticateUserUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthenticationService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  JwtTokenProvider jwtTokenProvider) {
        this.userRepository   = userRepository;
        this.passwordEncoder  = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public LoginSessionResult authenticate(AuthenticateUserCommand command) {
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

        // 5. Load tenant memberships (via JdbcTemplate — no TenantContext needed)
        List<UserMembershipInfo> memberships =
                userRepository.findMembershipsWithTenantInfo(user.getId());

        // 6. Generate short-lived loginToken (5min, scope=login_pending)
        //    This token is NOT usable for API access — JwtAuthFilter rejects scope=login_pending
        String loginToken = jwtTokenProvider.generateLoginToken(user.getId());

        return new LoginSessionResult(loginToken, memberships);
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
}

