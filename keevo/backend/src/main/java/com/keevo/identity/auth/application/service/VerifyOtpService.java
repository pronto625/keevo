package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.OtpChallenge;
import com.keevo.identity.auth.domain.model.OtpIdentifier;
import com.keevo.identity.auth.domain.model.OtpPurpose;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.VerifyOtpCommand;
import com.keevo.identity.auth.domain.port.in.VerifyOtpUseCase;
import com.keevo.identity.auth.domain.port.out.OtpChallengeRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Story 19.2 — checks a sign-in code and opens login step 1 (port of Bref's {@code OtpService.verifyOtp}).
 *
 * <p>Façade: store-review shortcut → daily failure cap → latest live challenge locked ({@code FOR UPDATE}) → code
 * check (wrong code: attempts + 1 committed, the 5th kills the challenge) → challenge consumed → account found (phone
 * or case-insensitive email) or created (OWNER, no password, no company) with the identifier marked verified →
 * {@code loginToken} + memberships, exactly like {@code /auth/login}. Account lockout is not consulted: the code is its
 * own factor.
 *
 * <p>{@code noRollbackFor = DomainException}: a wrong code's counted attempt is committed with the error answer.
 *
 * <p>Complexity: one indexed SUM, one {@code SELECT … FOR UPDATE LIMIT 1}, one UPDATE, one unique-index user lookup,
 * at most one INSERT (+ one re-read on conflict) or one UPDATE, one memberships JOIN.
 */
@Service
public class VerifyOtpService implements VerifyOtpUseCase {

    private static final Logger log = LoggerFactory.getLogger(VerifyOtpService.class);
    private static final Pattern CODE = Pattern.compile("^\\d{6}$");

    private final OtpSettings settings;
    private final OtpRateLimiter rateLimiter;
    private final OtpChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    public VerifyOtpService(OtpSettings settings,
                            OtpRateLimiter rateLimiter,
                            OtpChallengeRepository challengeRepository,
                            UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            JwtTokenProvider jwtTokenProvider,
                            Clock clock) {
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clock = clock;
    }

    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public LoginSessionResult verify(VerifyOtpCommand command) {
        // Per-IP limit first, for every identifier including the review account (fixed code, no failure count).
        rateLimiter.acquireVerify(command.clientIp());
        OtpIdentifier identifier = OtpIdentifier.parse(command.identifier(), settings.allowedPhonePrefixes());
        Instant now = clock.instant();
        check(identifier, command.code(), OtpPurpose.SIGN_IN, now);
        return openSession(identifier, now);
    }

    /**
     * Story 21.7 — the code check shared by every purpose (Template Method core), in the caller's transaction (which
     * must not roll back on {@link DomainException}, so a wrong code's attempt is kept): store-review shortcut → daily
     * failure cap of the purpose → latest live challenge of the purpose locked → code compared → challenge consumed.
     * The caller applies the per-IP limit first.
     *
     * @throws DomainException {@code OTP_INVALID} (no live code of this purpose, or wrong code),
     *                         {@code OTP_TOO_MANY_ATTEMPTS} (5th wrong code), {@code OTP_TOO_MANY_REQUESTS} (daily cap)
     */
    public void check(OtpIdentifier identifier, String rawCode, OtpPurpose purpose, Instant now) {
        String code = rawCode == null ? "" : rawCode.strip();
        if (settings.isReviewLogin(identifier, code)) {
            log.warn("Code of the store-review account accepted ({}, {})", identifier.masked(), purpose);
            return;
        }
        String key = purpose.challengeKey(identifier);
        int failures = challengeRepository.sumFailuresSince(key, now.minus(OtpSettings.DAILY_WINDOW));
        if (failures >= OtpSettings.DAILY_MAX_FAILURES) {
            log.warn("OTP blocked for {}: {} failed codes in 24 h", identifier.masked(), failures);
            throw new DomainException(ErrorCode.OTP_TOO_MANY_REQUESTS);
        }

        OtpChallenge challenge = challengeRepository.lockLatestActive(key, now)
                .orElseThrow(() -> new DomainException(ErrorCode.OTP_INVALID));

        if (!CODE.matcher(code).matches() || !passwordEncoder.matches(code, challenge.codeHash())) {
            int attempts = challengeRepository.recordFailure(challenge.id(), OtpSettings.MAX_ATTEMPTS, now);
            if (attempts >= OtpSettings.MAX_ATTEMPTS) {
                throw new DomainException(ErrorCode.OTP_TOO_MANY_ATTEMPTS);
            }
            throw new DomainException(ErrorCode.OTP_INVALID);
        }

        challengeRepository.consume(challenge.id(), now);
    }

    private LoginSessionResult openSession(OtpIdentifier identifier, Instant now) {
        Optional<User> existing = find(identifier);
        existing.ifPresent(VerifyOtpService::refuseSuperAdmin);
        User user = existing
                .map(found -> markVerified(found, identifier, now))
                .orElseGet(() -> createAccount(identifier, now));
        List<UserMembershipInfo> memberships = userRepository.findMembershipsWithTenantInfo(user.getId());
        return new LoginSessionResult(jwtTokenProvider.generateLoginToken(user.getId()), memberships);
    }

    private Optional<User> find(OtpIdentifier identifier) {
        return identifier.isPhone()
                ? userRepository.findByPhoneNumber(identifier.value())
                : userRepository.findByEmail(identifier.value());
    }

    /** Sets the verification date of the identifier the code was sent to, when not set yet. */
    private User markVerified(User user, OtpIdentifier identifier, Instant now) {
        if (identifier.isPhone() && user.getPhoneVerifiedAt() == null) {
            return userRepository.save(user.withPhoneVerifiedAt(now));
        }
        if (identifier.isEmail() && user.getEmailVerifiedAt() == null) {
            return userRepository.save(user.withEmailVerifiedAt(now));
        }
        return user;
    }

    /**
     * New account without company. A concurrent registration of the same phone or email makes the insert a no-op
     * (no aborted transaction): the account is re-read once and used; still absent → USER_ALREADY_EXISTS (the
     * identifier is held by a row the lookup cannot see, e.g. a username/phone conflict).
     */
    private User createAccount(OtpIdentifier identifier, Instant now) {
        User account = identifier.isPhone()
                ? User.newOtpAccount(identifier.value(), null, now)
                : User.newOtpAccount(null, identifier.value(), now);
        if (userRepository.insertIfAbsent(account)) {
            log.info("Account created by sign-in code for {}", identifier.masked());
            return account;
        }
        User existing = find(identifier)
                .orElseThrow(() -> new DomainException(ErrorCode.USER_ALREADY_EXISTS));
        refuseSuperAdmin(existing);
        return markVerified(existing, identifier, now);
    }

    /**
     * The super-admin never signs in by code: an SMS/WhatsApp code is weaker than its password login. Answered like a
     * wrong code (no account created, no verified date written).
     */
    private static void refuseSuperAdmin(User user) {
        if (user.getRole() == Role.SUPER_ADMIN) {
            throw new DomainException(ErrorCode.OTP_INVALID);
        }
    }
}
