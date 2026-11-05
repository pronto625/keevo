package com.keevo.identity.employee.application.service;

import com.keevo.shared.domain.PasswordLimits;
import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ChangePasswordCommand;
import com.keevo.identity.employee.domain.port.in.ChangePasswordUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final SessionTokenIssuer sessionTokenIssuer;
    private final TenantRepository tenantRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final UserMembershipRepository membershipRepository;

    public ChangePasswordService(UserRepository userRepository,
                                  EmployeeRepository employeeRepository,
                                  RefreshTokenRepository refreshTokenRepository,
                                  TokenRevocationPort tokenRevocationPort,
                                  PasswordEncoder passwordEncoder,
                                  SessionTokenIssuer sessionTokenIssuer,
                                  TenantRepository tenantRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  UserMembershipRepository membershipRepository) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRevocationPort = tokenRevocationPort;
        this.passwordEncoder = passwordEncoder;
        this.sessionTokenIssuer = sessionTokenIssuer;
        this.tenantRepository = tenantRepository;
        this.eventPublisher = eventPublisher;
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional
    public AuthTokens execute(ChangePasswordCommand command) {
        // 1. Load user
        User user = userRepository.findById(command.actorId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_CREDENTIALS));

        // 2. Verify current password (Story 12.12: > 72 bytes can never match — skip BCrypt, no 500)
        // Story 19.1 — an OTP/social-only account has no hash: same INVALID_CREDENTIALS answer (setting a first
        // password is story 19.2).
        if (!user.hasPassword()
                || PasswordLimits.exceedsBcryptLimit(command.currentPassword())
                || !passwordEncoder.matches(command.currentPassword(), user.getPasswordHash())) {
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS, "wrong current password");
        }

        // 3. Validate new password (≥8 chars, at least one digit)
        if (command.newPassword().length() < 8
                || PasswordLimits.exceedsBcryptLimit(command.newPassword())
                || !command.newPassword().matches(".*\\d.*")) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "password too short or missing digit");
        }

        // 3b. Story 19.6 — the session role is the one of the membership in the current company (as select-tenant
        // and refresh do), never the global users.role: a person may be OWNER in one company and EMPLOYEE in another.
        String tenantId = TenantContext.getCurrentTenant();
        Tenant tenant = tenantRepository.findBySchemaName(tenantId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        String role = membershipRepository.findByUserIdAndTenantId(command.actorId(), tenant.getId())
                .filter(UserTenantMembership::isActive)
                .map(UserTenantMembership::getRole)
                .orElseThrow(() -> new DomainException(ErrorCode.MEMBERSHIP_NOT_FOUND));

        // 4. Update password hash
        String newHash = passwordEncoder.encode(command.newPassword());
        User updatedUser = user.withPasswordHash(newHash);
        userRepository.save(updatedUser);

        // 5. Resolve role-specific data — EMPLOYEE or OWNER (Story 8.6 AC7)
        Optional<Employee> employeeOpt = employeeRepository.findByUserId(command.actorId());
        // Story 14.11 — the role is never derived from the employee row's existence (an employee promoted to OWNER
        // keeps it); Story 19.6 — it comes from the membership (step 3b).
        UUID employeeId = null;

        if (employeeOpt.isPresent()) {
            Employee emp = employeeOpt.get();
            employeeRepository.updatePasswordChangeRequired(emp.getId(), false);
            employeeId = emp.getId();
        }

        // 6. Story 16.11 — keep this device signed in: remember its session (sid) and label
        // before every refresh token of the user is revoked.
        UUID sessionId = CurrentActor.sessionIdOrNull();
        DeviceInfo device = DeviceInfo.NONE;
        if (sessionId != null && tenantId != null) {
            device = refreshTokenRepository.findLiveSessions(command.actorId(), tenantId, Instant.now()).stream()
                    .filter(t -> sessionId.equals(t.effectiveSessionId()))
                    .findFirst()
                    .map(t -> DeviceInfo.of(t.deviceId(), t.deviceLabel()))
                    .orElse(DeviceInfo.NONE);
        }
        refreshTokenRepository.revokeAllByUserId(command.actorId());

        // Story 12.2 — revoke access tokens issued before now. Password change = credential
        // compromise → revoke across ALL the user's memberships (multi-tenant). Must execute BEFORE
        // step 7 (new token issuance) so the new token's iat >= tokens_valid_after.
        tokenRevocationPort.revokeAllSessionsEverywhere(command.actorId());

        // 7. Generate fresh tokens (same path as login / refresh: identity-only access token).
        AuthTokens tokens = sessionTokenIssuer.issue(command.actorId(), tenant, role, sessionId, device).tokens();

        // 8. Publish event after all writes are complete (EMPLOYEE only)
        if (employeeId != null) {
            eventPublisher.publishEvent(new EmployeePasswordSetEvent(
                    command.actorId(), employeeId, Instant.now()));
        }
        return tokens;
    }
}
