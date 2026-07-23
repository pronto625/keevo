package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PasswordResetEvent;
import com.keevo.identity.auth.domain.model.PasswordResetToken;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.ResetPasswordCommand;
import com.keevo.identity.auth.domain.port.in.ResetPasswordUseCase;
import com.keevo.identity.auth.domain.port.out.PasswordResetTokenRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * ResetPasswordService — Orchestrates the reset-password flow.
 *
 * <p>Story 14.12 — Verifies OTP code, resets password, revokes all sessions,
 * and publishes {@link PasswordResetEvent}. Does NOT issue new tokens
 * (user must re-login via normal two-step flow).
 *
 * <p>Order of checks (AC3, Piège n°4):
 * <ol>
 *   <li>Token found?</li>
 *   <li>Expired?</li>
 *   <li>attempts >= 5? (BEFORE hash comparison — lockout supersedes)</li>
 *   <li>Hash match?</li>
 *   <li>New password valid?</li>
 * </ol>
 */
@Service
public class ResetPasswordService implements ResetPasswordUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResetPasswordService.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final TokenRevocationPort tokenRevocationPort;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public ResetPasswordService(PasswordResetTokenRepository passwordResetTokenRepository,
                                 UserRepository userRepository,
                                 EmployeeRepository employeeRepository,
                                 TokenRevocationPort tokenRevocationPort,
                                 PasswordEncoder passwordEncoder,
                                 ApplicationEventPublisher eventPublisher) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.tokenRevocationPort = tokenRevocationPort;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void execute(ResetPasswordCommand command) {
        String phone = command.phoneNumber();

        // 1. Find active token for this phone number
        PasswordResetToken token = passwordResetTokenRepository.findActiveByPhoneNumber(phone)
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_OR_EXPIRED_CODE));

        // 2. Check expiry (do NOT increment attempts on expired token — AC3)
        if (token.isExpired()) {
            throw new DomainException(ErrorCode.INVALID_OR_EXPIRED_CODE);
        }

        // 3. Check lockout BEFORE hash comparison (Piège n°4)
        if (token.isLocked()) {
            throw new DomainException(ErrorCode.CODE_LOCKED);
        }

        // 4. Verify code hash
        if (!passwordEncoder.matches(command.code(), token.codeHash())) {
            // Increment attempts
            PasswordResetToken updated = token.withAttempts(token.attempts() + 1);
            passwordResetTokenRepository.save(updated);
            throw new DomainException(ErrorCode.INVALID_OR_EXPIRED_CODE);
        }

        // 5. Validate new password (same rules as ChangePasswordService, duplicated per convention)
        // Max-length guard BEFORE bcrypt.encode() — bcrypt cost 12 on very long strings
        // is CPU-expensive (DoS vector on public endpoint, no rate-limit per IP here).
        String newPassword = command.newPassword();
        if (newPassword.length() < 8 || newPassword.length() > 128
                || !newPassword.matches(".*\\d.*")) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "password length must be 8-128 and contain at least one digit");
        }

        // 6. Update password hash on User
        User user = userRepository.findById(token.userId())
                .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));
        String newHash = passwordEncoder.encode(newPassword);
        User updatedUser = user.withPasswordHash(newHash);
        userRepository.save(updatedUser);

        // 7. Consume the token
        PasswordResetToken consumed = token.withConsumed(Instant.now());
        passwordResetTokenRepository.save(consumed);

        // 8. Clear passwordChangeRequired flag on Employee (if employee record exists)
        // Mirror ChangePasswordService.java L101-107 — user may be OWNER without employee record
        Optional<Employee> employeeOpt = employeeRepository.findByUserId(user.getId());
        employeeOpt.ifPresent(emp ->
                employeeRepository.updatePasswordChangeRequired(emp.getId(), false));

        // 9. Revoke ALL sessions across all tenants (reuse Story 12.2 — ChangePasswordService L118)
        tokenRevocationPort.revokeAllSessionsEverywhere(user.getId());

        // 10. Publish event (AC3 — after all writes)
        eventPublisher.publishEvent(new PasswordResetEvent(user.getId(), Instant.now()));

        log.info("reset-password: password reset successful for userId={}", user.getId());
    }
}
