package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetByOwnerEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.SetEmployeePasswordCommand;
import com.keevo.identity.employee.domain.port.in.SetEmployeePasswordUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * SetEmployeePasswordService — Owner sets a new password for an employee,
 * forcing passwordChangeRequired = true and revoking all sessions.
 *
 * <p>Story 14.11 — AC3.
 */
@Service
public class SetEmployeePasswordService implements SetEmployeePasswordUseCase {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRevocationPort tokenRevocationPort;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public SetEmployeePasswordService(EmployeeRepository employeeRepository,
                                       UserRepository userRepository,
                                       RefreshTokenRepository refreshTokenRepository,
                                       TokenRevocationPort tokenRevocationPort,
                                       PasswordEncoder passwordEncoder,
                                       ApplicationEventPublisher eventPublisher) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRevocationPort = tokenRevocationPort;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void execute(SetEmployeePasswordCommand command) {
        // 0. Validate password strength (same rule as ChangePasswordService: ≥8 chars, ≥1 digit)
        String newPassword = command.newPassword();
        if (newPassword == null || newPassword.length() < 8 || !newPassword.matches(".*\\d.*")) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Password must be at least 8 characters with at least 1 digit");
        }

        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // 1b. Self-check: owner cannot set own password via this endpoint
        // (must use ChangePasswordService which requires the current password)
        if (employee.getUserId().equals(command.actorId())) {
            throw new DomainException(ErrorCode.CANNOT_SET_OWN_PASSWORD,
                    "You cannot set your own password through this endpoint");
        }

        // 2. Load user
        User user = userRepository.findById(employee.getUserId())
                .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));

        // 3. Update password hash (bcrypt cost 12)
        String hash = passwordEncoder.encode(newPassword);
        user = user.withPasswordHash(hash);
        userRepository.save(user);

        // 4. Force passwordChangeRequired = true
        employeeRepository.updatePasswordChangeRequired(employee.getId(), true);

        // 5. Revoke refresh tokens (force re-login — pattern DeactivateEmployeeService/ChangePasswordService)
        refreshTokenRepository.revokeAllByUserId(employee.getUserId());

        // 6. Revoke access tokens (tenant-scoped)
        String tenantSchema = TenantContext.getCurrentTenant();
        if (tenantSchema == null) {
            throw new IllegalStateException(
                    "TenantContext required to revoke sessions for employee " + employee.getUserId());
        }
        tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema);

        // 7. Publish event (NOT EmployeePasswordSetEvent — that's for self-service)
        eventPublisher.publishEvent(new EmployeePasswordSetByOwnerEvent(
                command.actorId(), tenantSchema, employee.getId(), Instant.now()));
    }
}
