package com.keevo.identity.auth.application.service;

import com.keevo.shared.domain.PasswordLimits;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserUseCase;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AuthenticationService — Orchestrates user login step 1 (two-step login, Story 1.7).
 *
 * <p>GoF Pattern: Façade — coordinates:
 * account lookup → account lockout check → password verification → membership loading → loginToken generation.
 *
 * <p>Story 19.4 — Strategy by field for the account lookup ({@link #findAccount}): phone or username, chosen from the
 * command; the rest of the flow (lockout, password, memberships, loginToken) is shared.
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
    private static final String INVALID_USERNAME_CREDENTIALS = "Identifiant ou mot de passe incorrect";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    /**
     * Story 19.4 — hash checked against when a username is unknown, so the answer takes the time of a wrong password
     * (no username enumeration by timing, as Bref). Encoded once with the application encoder (same cost).
     */
    private final String dummyHash;
    private final LoginRateLimiter rateLimiter;

    /** Without an explicit limiter (unit tests): the default per-IP brake on the system clock. */
    public AuthenticationService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  JwtTokenProvider jwtTokenProvider) {
        this(userRepository, passwordEncoder, jwtTokenProvider, new LoginRateLimiter(Clock.systemUTC(), 50));
    }

    @Autowired
    public AuthenticationService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  JwtTokenProvider jwtTokenProvider,
                                  LoginRateLimiter rateLimiter) {
        this.userRepository   = userRepository;
        this.passwordEncoder  = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.rateLimiter      = rateLimiter;
        this.dummyHash        = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Story 19.4 — per-IP brake checked before the lookup; only a failed login ({@code INVALID_CREDENTIALS}, either
     * path) is counted, never a success nor a locked account.
     */
    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public LoginSessionResult authenticate(AuthenticateUserCommand command) {
        rateLimiter.checkAllowed(command.clientIp());
        try {
            return authenticateChecked(command);
        } catch (DomainException e) {
            if (ErrorCode.INVALID_CREDENTIALS.name().equals(e.getDomainCode())) {
                rateLimiter.recordFailure(command.clientIp());
            }
            throw e;
        }
    }

    private LoginSessionResult authenticateChecked(AuthenticateUserCommand command) {
        // 1. Load user — return INVALID_CREDENTIALS (not USER_NOT_FOUND) to avoid user enumeration.
        // Story 19.4 — strategy by field: the username path and the phone path differ only by this lookup.
        User user = findAccount(command)
                .orElseThrow(() -> invalidCredentials(command));

        // 2. Check account lockout BEFORE verifying password
        if (isLocked(user)) {
            throw new DomainException(ErrorCode.ACCOUNT_LOCKED);
        }

        // 3. Verify password — increment counter on failure.
        // Story 12.12: > 72 UTF-8 bytes can never match (BCrypt would throw → 500) — treat as a
        // wrong password without calling matches().
        // Story 19.1: an account without a password (OTP/social only) can never match. Not counted as a failed
        // attempt — otherwise anyone could lock such an account with 5 password tries.
        if (!user.hasPassword()) {
            if (command.usesUsername()) {
                burnPasswordCheck(command.password()); // Story 19.4 — same timing as a wrong password
            }
            throw invalidCredentials(command);
        }
        // Story 19.4 — any bcrypt hash ($2a$/$2b$/$2y$, any cost — Bref's are $2b$12$) is checked as is, never rehashed.
        if (PasswordLimits.exceedsBcryptLimit(command.password())
                || !passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            user = incrementFailedAttempts(user);
            userRepository.save(user);
            throw invalidCredentials(command);
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

    /**
     * Phone path: canonical +<digits> form (Story 12.12 — same identity for '237…' and '+237…').
     * Username path (Story 19.4): stripped, case-insensitive lookup; an unknown username costs one bcrypt like a wrong
     * password; a super-admin, or a social account's technical username ({@code google-…}/{@code apple-…}), is never
     * signed in by username (answered like an unknown account, nothing counted on the account).
     */
    private Optional<User> findAccount(AuthenticateUserCommand command) {
        if (!command.usesUsername()) {
            return userRepository.findByPhoneNumber(PhoneNumbers.normalize(command.phoneNumber()));
        }
        Optional<User> user = userRepository.findByUsername(command.username())
                .filter(u -> u.getRole() != Role.SUPER_ADMIN)
                .filter(u -> !Usernames.isTechnical(u.getUsername()));
        if (user.isEmpty()) {
            burnPasswordCheck(command.password());
        }
        return user;
    }

    /**
     * INVALID_CREDENTIALS; on the username path (Story 19.4) its French text names the identifier the user typed
     * (the default text says « Téléphone »).
     */
    private static DomainException invalidCredentials(AuthenticateUserCommand command) {
        return command.usesUsername()
                ? DomainException.userFacing(ErrorCode.INVALID_CREDENTIALS, INVALID_USERNAME_CREDENTIALS)
                : new DomainException(ErrorCode.INVALID_CREDENTIALS);
    }

    /** One bcrypt comparison whose result is ignored (timing only); never fails on an over-long password. */
    private void burnPasswordCheck(String password) {
        if (password != null && !PasswordLimits.exceedsBcryptLimit(password)) {
            passwordEncoder.matches(password, dummyHash);
        }
    }

    private boolean isLocked(User user) {
        return user.lockedUntil() != null && Instant.now().isBefore(user.lockedUntil());
    }

    private User incrementFailedAttempts(User user) {
        int newCount = user.failedAttempts() + 1;
        Instant newLockedUntil = null;
        if (newCount >= MAX_FAILED_ATTEMPTS) {
            newLockedUntil = Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES);
            if (user.hasPhoneNumber()) {
                log.warn("Account locked for phone ending in ...{} until {}", phoneTail(user.getPhoneNumber()),
                        newLockedUntil);
            } else {
                log.warn("Account locked for user {} until {}", user.getId(), newLockedUntil); // Story 19.4
            }
        }
        return user.withLockoutState(newCount, newLockedUntil);
    }

    /** Last 4 digits for the lockout log; {@code "***"} when the account has no (or a short) phone — Story 19.1. */
    static String phoneTail(String phone) {
        return phone != null && phone.length() >= 4 ? phone.substring(phone.length() - 4) : "***";
    }
}

