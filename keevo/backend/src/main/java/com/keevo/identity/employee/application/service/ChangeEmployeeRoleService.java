package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.event.catalog.employee.EmployeeRoleChangedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.role.domain.port.out.CustomRoleStore;
import com.keevo.shared.application.port.TransactionLockPort;
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
 * ChangeEmployeeRoleService — Changes an employee's role within a tenant (Story 19.5: OWNER, MANAGER, EMPLOYEE,
 * VIEWER).
 * Enforces anti-lockout: cannot change own role, cannot demote last active owner.
 * Also synchronizes the global {@code public.users.role} to prevent stale JWT roles
 * on refresh/password-change (see "Régression critique" in story 14.11).
 *
 * <p>Story 14.11 — AC2.
 */
@Service
public class ChangeEmployeeRoleService implements ChangeEmployeeRoleUseCase {

    static final String STORE_REQUIRED_MESSAGE = "Affectez d'abord ce membre à une boutique";

    /** Story 19.5 — the membership roles an owner may assign. */
    private static final Set<String> VALID_ROLES = Set.copyOf(Role.MEMBERSHIP_ROLE_NAMES);

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRevocationPort tokenRevocationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionLockPort transactionLock;
    private final SharedAccountGuard sharedAccountGuard;
    /** Story 19.14 — choosing a built-in role drops the member's owner-defined role; {@code null} in unit tests. */
    private final CustomRoleStore customRoles;

    /** Without the custom roles store (unit tests): the custom role of the member is left as is. */
    public ChangeEmployeeRoleService(EmployeeRepository employeeRepository,
                                      UserRepository userRepository,
                                      UserMembershipRepository membershipRepository,
                                      TenantRepository tenantRepository,
                                      RefreshTokenRepository refreshTokenRepository,
                                      TokenRevocationPort tokenRevocationPort,
                                      ApplicationEventPublisher eventPublisher,
                                      TransactionLockPort transactionLock,
                                      SharedAccountGuard sharedAccountGuard) {
        this(employeeRepository, userRepository, membershipRepository, tenantRepository, refreshTokenRepository,
                tokenRevocationPort, eventPublisher, transactionLock, sharedAccountGuard, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChangeEmployeeRoleService(EmployeeRepository employeeRepository,
                                      UserRepository userRepository,
                                      UserMembershipRepository membershipRepository,
                                      TenantRepository tenantRepository,
                                      RefreshTokenRepository refreshTokenRepository,
                                      TokenRevocationPort tokenRevocationPort,
                                      ApplicationEventPublisher eventPublisher,
                                      TransactionLockPort transactionLock,
                                      SharedAccountGuard sharedAccountGuard,
                                      CustomRoleStore customRoles) {
        this.customRoles = customRoles;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRevocationPort = tokenRevocationPort;
        this.eventPublisher = eventPublisher;
        this.transactionLock = transactionLock;
        this.sharedAccountGuard = sharedAccountGuard;
    }

    @Override
    @Transactional
    public void execute(ChangeEmployeeRoleCommand command) {
        // 0. Validate role
        if (command.newRole() == null || !VALID_ROLES.contains(command.newRole())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "role must be OWNER, MANAGER, EMPLOYEE or VIEWER");
        }

        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // 2. Self-demotion check (compare userId, not employeeId)
        if (employee.getUserId().equals(command.actorId())) {
            throw new DomainException(ErrorCode.CANNOT_CHANGE_OWN_ROLE);
        }

        // 2b. Story 19.5 — a store member (MANAGER, EMPLOYEE, VIEWER) is pinned to a store: a member without store
        // (e.g. a former owner) would be refused everywhere, so he must be assigned to a store first.
        if (Role.isStoreMember(command.newRole()) && employee.getStoreId() == null) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, STORE_REQUIRED_MESSAGE);
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

        // Story 19.14 — a built-in role chosen here replaces the member's owner-defined role (if any), even when the
        // built-in role is the same (« Employé » instead of « Caissier »). No row is written when he holds none.
        if (customRoles != null) {
            customRoles.assign(tenant.getId(), employee.getUserId(), null);
        }

        // No-op if same role
        if (previousRole.equals(command.newRole())) {
            return;
        }

        // 5. Anti-lockout: cannot demote last active owner (Story 19.5: OWNER → any other role)
        if (Role.OWNER.name().equals(previousRole)) {
            // Story 13.9: two owners demoting each other concurrently — serialise per tenant so
            // the second count sees the first demotion (committed before the lock is released).
            transactionLock.lock("owners:" + tenantSchema);
            List<User> activeOwners = userRepository.findOwnersByTenantSchemaName(tenantSchema);
            if (activeOwners.size() <= 1) {
                throw new DomainException(ErrorCode.CANNOT_DEMOTE_LAST_OWNER);
            }
        }

        // Story 19.6 — evaluated on the membership BEFORE the change (OWNER here counts as shared).
        boolean sharedAccount = sharedAccountGuard.isShared(employee.getUserId(), tenant.getId(),
                java.util.Optional.of(membership));

        // 6. Update membership role (tenant-scoped)
        membershipRepository.updateRole(employee.getUserId(), tenant.getId(), command.newRole());

        // 7-8. Story 19.6 — a shared account keeps its global users.role (informational, it belongs to the person)
        // and only this company's refresh tokens are revoked; otherwise unchanged: global role kept in step and every
        // refresh token revoked (force re-login — pattern DeactivateEmployeeService).
        if (sharedAccount) {
            refreshTokenRepository.revokeAllByUserIdAndTenantId(employee.getUserId(), tenantSchema);
        } else {
            User user = userRepository.findById(employee.getUserId())
                    .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));
            userRepository.save(user.withRole(Role.valueOf(command.newRole())));
            refreshTokenRepository.revokeAllByUserId(employee.getUserId());
        }

        // 9. Revoke access tokens (tenant-scoped, not everywhere)
        tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema);

        // 10. Publish event
        eventPublisher.publishEvent(new EmployeeRoleChangedEvent(
                command.actorId(), tenantSchema, employee.getId(),
                previousRole, command.newRole(), Instant.now()));
    }
}
