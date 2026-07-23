package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
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
import java.util.Optional;
import java.util.UUID;

/**
 * ChangePasswordService — Orchestrates password change for EMPLOYEE and OWNER (Story 3.5 AC4, Story 8.6 AC7).
 *
 * <p>GoF Strategy pattern: {@code PostPasswordChangeTokenStrategy} is implicit in the
 * conditional branching by role — adding MANAGER requires only a new branch.
 *
 * <p>Story 8.6 AC7: OWNER has no employee record. The previous {@code orElseThrow(EMPLOYEE_NOT_FOUND)}
 * is replaced with {@code Optional} handling so OWNER callers get a valid token with role=OWNER.
 */
@Service
public class ChangePasswordService implements ChangePasswordUseCase {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRevocationPort tokenRevocationPort;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final ApplicationEventPublisher eventPublisher;

    public ChangePasswordService(UserRepository userRepository,
                                  EmployeeRepository employeeRepository,
                                  RefreshTokenRepository refreshTokenRepository,
                                  TokenRevocationPort tokenRevocationPort,
                                  PasswordEncoder passwordEncoder,
                                  JwtTokenProvider jwtTokenProvider,
                                  JwtProperties jwtProperties,
                                  ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRevocationPort = tokenRevocationPort;
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

        // 5. Resolve role-specific data — EMPLOYEE or OWNER (Story 8.6 AC7)
        Optional<Employee> employeeOpt = employeeRepository.findByUserId(command.actorId());
        // Story 14.11 — derive role from User.role, NOT from employee existence heuristic.
        // An employee promoted to OWNER still has an employee record, so employeeOpt.isPresent()
        // would incorrectly return "EMPLOYEE". User.role is kept in sync by ChangeEmployeeRoleService.
        String role = updatedUser.getRole().name();
        UUID storeId = null;
        String firstName = null;
        UUID employeeId = null;

        if (employeeOpt.isPresent()) {
            Employee emp = employeeOpt.get();
            employeeRepository.updatePasswordChangeRequired(emp.getId(), false);
            storeId    = emp.getStoreId();
            firstName  = emp.getFirstName();
            employeeId = emp.getId();
        }

        // 6. Revoke all existing refresh tokens
        refreshTokenRepository.revokeAllByUserId(command.actorId());

        // Story 12.2 — revoke access tokens issued before now (<5min propagation, NFR12).
        // Password change = credential compromise → revoke across ALL the user's memberships
        // (multi-tenant, not just the current tenant — a token for another tenant would otherwise
        // stay valid). Must execute BEFORE step 7 (new token issuance) so the new token's
        // iat >= tokens_valid_after.
        String tenantId = TenantContext.getCurrentTenant();
        tokenRevocationPort.revokeAllSessionsEverywhere(command.actorId());

        // 7. Generate fresh tokens
        String accessToken = jwtTokenProvider.generateAccessToken(
                command.actorId(), tenantId, role, "ACTIVE",
                storeId, false, firstName);
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String tokenHash = hashToken(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        refreshTokenRepository.save(RefreshToken.create(
                command.actorId(), tenantId, tokenHash, expiresAt));

        // 8. Publish event after all writes are complete (EMPLOYEE only)
        if (employeeId != null) {
            eventPublisher.publishEvent(new EmployeePasswordSetEvent(
                    command.actorId(), employeeId, Instant.now()));
        }

        long expiresIn = (long) jwtProperties.getAccessTokenExpiryHours() * 3600;
        return new AuthTokens(accessToken, rawRefreshToken, expiresIn,
                command.actorId(), tenantId, role,
                storeId != null ? storeId.toString() : null, false);
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
