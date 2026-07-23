package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PasswordResetRequestedEvent;
import com.keevo.identity.auth.domain.model.PasswordResetToken;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.RequestPasswordResetCommand;
import com.keevo.identity.auth.domain.port.in.RequestPasswordResetUseCase;
import com.keevo.identity.auth.domain.port.out.PasswordResetTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * RequestPasswordResetService — Orchestrates the forgot-password flow.
 *
 * <p>Story 14.12 — Anti-enumeration: always returns silently (never throws).
 * Rate-limits by phone number, generates a 6-digit OTP, hashes it (bcrypt),
 * persists the token, and sends via WhatsApp.
 */
@Service
public class RequestPasswordResetService implements RequestPasswordResetUseCase {

    private static final Logger log = LoggerFactory.getLogger(RequestPasswordResetService.class);

    private final PasswordResetRateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final WhatsAppPort whatsAppPort;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom secureRandom = new SecureRandom();

    public RequestPasswordResetService(PasswordResetRateLimiter rateLimiter,
                                        UserRepository userRepository,
                                        PasswordResetTokenRepository passwordResetTokenRepository,
                                        PasswordEncoder passwordEncoder,
                                        WhatsAppPort whatsAppPort,
                                        ApplicationEventPublisher eventPublisher) {
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.whatsAppPort = whatsAppPort;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void execute(RequestPasswordResetCommand command) {
        String phone = command.phoneNumber();

        // Rate-limit check FIRST — if exceeded, silently return (D1)
        if (!rateLimiter.tryAcquire(phone)) {
            log.debug("forgot-password rate-limited for phone={}", phone);
            return;
        }

        // Look up user — if not found, silently return (anti-enumeration)
        Optional<User> userOpt = userRepository.findByPhoneNumber(phone);
        if (userOpt.isEmpty()) {
            // Timing oracle mitigation (AC2 / D1): execute a dummy bcrypt encode so the
            // "user not found" path takes comparable time to the "user found" path
            // (which does bcrypt.encode + DB write + WhatsApp send). Without this, an
            // attacker measuring response times could distinguish existing vs non-existing
            // phone numbers, breaking anti-enumeration.
            passwordEncoder.encode("dummy-otp-timing-pad");
            log.debug("forgot-password: no user found for phone={}", phone);
            return;
        }

        User user = userOpt.get();

        // Generate 6-digit OTP
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code);

        // Invalidate all previous unconsumed tokens for this user
        passwordResetTokenRepository.invalidateActiveTokensForUser(user.getId());

        // Persist new token (expires in 10 minutes)
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        PasswordResetToken token = PasswordResetToken.create(
                user.getId(), phone, codeHash, expiresAt);
        passwordResetTokenRepository.save(token);

        // Send OTP via WhatsApp
        whatsAppPort.sendOtp(phone, code);

        // Publish event (only if user exists — anti-enumeration, AC2)
        eventPublisher.publishEvent(new PasswordResetRequestedEvent(
                user.getId(), phone, Instant.now()));

        log.info("forgot-password: OTP generated and sent for userId={}", user.getId());
    }
}
