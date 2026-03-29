package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ChangePasswordCommand;
import com.keevo.identity.employee.domain.port.in.ChangePasswordUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * ChangePasswordService — Orchestrates employee password change (Story 3.5 AC4).
 */
@Service
public class ChangePasswordService implements ChangePasswordUseCase {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final ApplicationEventPublisher eventPublisher;

    public ChangePasswordService(UserRepository userRepository,
                                  EmployeeRepository employeeRepository,
                                  RefreshTokenRepository refreshTokenRepository,
                                  PasswordEncoder passwordEncoder,
                                  JwtTokenProvider jwtTokenProvider,
                                  JwtProperties jwtProperties,
                                  ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public AuthTokens execute(ChangePasswordCommand command) {
        // 1. Load user
        User user = userRepository.findById(command.actorId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_CREDENTIALS));

        // 2. Verify current password
        if (!passwordEncoder.matches(command.currentPassword(), user.getPasswordHash())) {
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS, "wrong current password");
        }

        // 3. Validate new password (≥8 chars, at least one digit)
        if (command.newPassword().length() < 8
                || !command.newPassword().matches(".*\\d.*")) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "password too short or missing digit");
        }

        // 4. Update password hash
        String newHash = passwordEncoder.encode(command.newPassword());
        User updatedUser = user.withPasswordHash(newHash);
        userRepository.save(updatedUser);

        // 5. Find employee and clear passwordChangeRequired flag
        Employee employee = employeeRepository.findByUserId(command.actorId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
        employeeRepository.updatePasswordChangeRequired(employee.getId(), false);

        // 6. Revoke all existing refresh tokens
        refreshTokenRepository.revokeAllByUserId(command.actorId());

        // 7. Generate fresh tokens
        String tenantId = TenantContext.getCurrentTenant();
        String accessToken = jwtTokenProvider.generateAccessToken(
                command.actorId(), tenantId, "EMPLOYEE", "ACTIVE",
                employee.getStoreId(), false, employee.getFirstName());
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String tokenHash = hashToken(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        refreshTokenRepository.save(RefreshToken.create(
                command.actorId(), tenantId, tokenHash, expiresAt));

        // 8. Publish event
        eventPublisher.publishEvent(new EmployeePasswordSetEvent(
                command.actorId(), employee.getId(), Instant.now()));

        long expiresIn = (long) jwtProperties.getAccessTokenExpiryHours() * 3600;
        return new AuthTokens(accessToken, rawRefreshToken, expiresIn,
                command.actorId(), tenantId, "EMPLOYEE",
                employee.getStoreId().toString(), false);
    }

    private String hashToken(String rawToken) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
