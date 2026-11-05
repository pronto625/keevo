package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeDeactivatedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * DeactivateEmployeeService — Revokes employee access (Story 3.5 AC7).
 *
 * <p>Story 19.6 — for a shared account (membership in another company, or OWNER here) only this company's membership
 * and refresh tokens are closed; the other companies' sessions are untouched.
 */
@Service
public class DeactivateEmployeeService implements DeactivateEmployeeUseCase {

    private final EmployeeRepository employeeRepository;
    private final UserMembershipRepository membershipRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRevocationPort tokenRevocationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TransactionLockPort transactionLock;
    private final SharedAccountGuard sharedAccountGuard;

    public DeactivateEmployeeService(EmployeeRepository employeeRepository,
                                      UserMembershipRepository membershipRepository,
                                      RefreshTokenRepository refreshTokenRepository,
                                      TokenRevocationPort tokenRevocationPort,
                                      ApplicationEventPublisher eventPublisher,
                                      TenantRepository tenantRepository,
                                      UserRepository userRepository,
                                      TransactionLockPort transactionLock,
                                      SharedAccountGuard sharedAccountGuard) {
        this.employeeRepository = employeeRepository;
        this.membershipRepository = membershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRevocationPort = tokenRevocationPort;
        this.eventPublisher = eventPublisher;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.transactionLock = transactionLock;
        this.sharedAccountGuard = sharedAccountGuard;
    }

    @Override
    @Transactional
    public void execute(DeactivateEmployeeCommand command) {
        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // 1b. Story 13.9 anti-lockout: deactivating an employee row whose ACTIVE membership in
        // this tenant is OWNER must not leave the tenant without an active owner. Same per-tenant
        // lock as ChangeEmployeeRoleService so a concurrent demotion/deactivation is serialised.
        // Fail closed: the tenant must resolve (the JWT filter always sets it).
        String currentSchema = TenantContext.getCurrentTenant();
        if (currentSchema == null) {
            throw new IllegalStateException(
                    "TenantContext required to deactivate employee " + employee.getUserId());
        }
        UUID tenantId = tenantRepository.findBySchemaName(currentSchema)
                .map(Tenant::getId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        var membership = membershipRepository.findByUserIdAndTenantId(employee.getUserId(), tenantId);
        boolean isActiveOwner = membership
                .filter(UserTenantMembership::isActive)
                .map(m -> "OWNER".equals(m.getRole()))
                .orElse(false);
        // Story 19.6 — shared account (membership elsewhere or OWNER here): only this company's access is closed.
        boolean sharedAccount = sharedAccountGuard.isShared(employee.getUserId(), tenantId, membership);
        if (isActiveOwner) {
            transactionLock.lock("owners:" + currentSchema);
            if (userRepository.findOwnersByTenantSchemaName(currentSchema).size() <= 1) {
                throw new DomainException(ErrorCode.CANNOT_DEMOTE_LAST_OWNER);
            }
        }

        // 2. Set status INACTIVE
        employeeRepository.updateStatus(command.employeeId(), EmployeeStatus.INACTIVE);

        // 3. Deactivate the membership of THIS tenant only (v1s-13-9 follow-up: the user may
        // belong to — or own — other shops, which must keep their access).
        membershipRepository.deactivate(employee.getUserId(), tenantId);

        // 4. Revoke refresh tokens — Story 19.6: for a shared account only this company's (one UPDATE by user and
        // tenant); the person's sessions in their other companies stay valid.
        if (sharedAccount) {
            refreshTokenRepository.revokeAllByUserIdAndTenantId(employee.getUserId(), currentSchema);
        } else {
            refreshTokenRepository.revokeAllByUserId(employee.getUserId());
        }

        // Story 12.2 — revoke access tokens issued before now (<5min propagation, NFR12).
        // Subject = the deactivated EMPLOYEE (employee.getUserId()), NOT the OWNER actor.
        // An employee is scoped to a single tenant → per-tenant revoke (not everywhere).
        String tenantSchema = TenantContext.getCurrentTenant();
        if (tenantSchema == null) {
            throw new IllegalStateException(
                    "TenantContext required to revoke sessions for employee " + employee.getUserId());
        }
        tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema);

        // 5. Publish event
        eventPublisher.publishEvent(new EmployeeDeactivatedEvent(
                command.actorId(), command.employeeId(),
                employee.getUserId(), Instant.now()));
    }
}
