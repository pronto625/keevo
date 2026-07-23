package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeRoleChangedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * ChangeEmployeeRoleService — Changes an employee's role within a tenant.
 * Enforces anti-lockout: cannot change own role, cannot demote last active owner.
 * Also synchronizes the global {@code public.users.role} to prevent stale JWT roles
 * on refresh/password-change (see "Régression critique" in story 14.11).
 *
 * <p>Story 14.11 — AC2.
 */
@Service
public class ChangeEmployeeRoleService implements ChangeEmployeeRoleUseCase {

    private static final Set<String> VALID_ROLES = Set.of("OWNER", "EMPLOYEE");

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRevocationPort tokenRevocationPort;
    private final ApplicationEventPublisher eventPublisher;

    public ChangeEmployeeRoleService(EmployeeRepository employeeRepository,
                                      UserRepository userRepository,
                                      UserMembershipRepository membershipRepository,
                                      TenantRepository tenantRepository,
                                      RefreshTokenRepository refreshTokenRepository,
                                      TokenRevocationPort tokenRevocationPort,
                                      ApplicationEventPublisher eventPublisher) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRevocationPort = tokenRevocationPort;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void execute(ChangeEmployeeRoleCommand command) {
        // 0. Validate role
        if (command.newRole() == null || !VALID_ROLES.contains(command.newRole())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "role must be OWNER or EMPLOYEE");
        }

        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // 2. Self-demotion check (compare userId, not employeeId)
        if (employee.getUserId().equals(command.actorId())) {
            throw new DomainException(ErrorCode.CANNOT_CHANGE_OWN_ROLE);
        }

        // 3. Resolve tenantId from schema
        String tenantSchema = TenantContext.getCurrentTenant();
        var tenant = tenantRepository.findBySchemaName(tenantSchema)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));

        // 4. Get current role
        UserTenantMembership membership = membershipRepository
                .findByUserIdAndTenantId(employee.getUserId(), tenant.getId())
                .orElseThrow(() -> new DomainException(ErrorCode.MEMBERSHIP_NOT_FOUND));
        String previousRole = membership.getRole();

        // No-op if same role
        if (previousRole.equals(command.newRole())) {
            return;
        }

        // 5. Anti-lockout: cannot demote last active owner
        if ("OWNER".equals(previousRole) && "EMPLOYEE".equals(command.newRole())) {
            List<User> activeOwners = userRepository.findOwnersByTenantSchemaName(tenantSchema);
            if (activeOwners.size() <= 1) {
                throw new DomainException(ErrorCode.CANNOT_DEMOTE_LAST_OWNER);
            }
        }

        // 6. Update membership role (tenant-scoped)
        membershipRepository.updateRole(employee.getUserId(), tenant.getId(), command.newRole());

        // 7. Sync global User.role (fixes stale role on refresh/password-change)
        User user = userRepository.findById(employee.getUserId())
                .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));
        user = user.withRole(Role.valueOf(command.newRole()));
        userRepository.save(user);

        // 8. Revoke refresh tokens (force re-login — pattern DeactivateEmployeeService)
        refreshTokenRepository.revokeAllByUserId(employee.getUserId());

        // 9. Revoke access tokens (tenant-scoped, not everywhere)
        tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema);

        // 10. Publish event
        eventPublisher.publishEvent(new EmployeeRoleChangedEvent(
                command.actorId(), tenantSchema, employee.getId(),
                previousRole, command.newRole(), Instant.now()));
    }
}
