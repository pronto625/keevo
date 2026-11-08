package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.RemoveEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Story 20.13 — removal of a member (Bref {@code DELETE /users/:id}, super-admin): refused for an owner of the
 * account; otherwise the deactivation rules apply first ({@link DeactivateEmployeeService}: status INACTIVE, membership
 * closed, refresh tokens of this account revoked, access tokens cut, event audited), then the membership row is
 * deleted so the person is no longer a member of the account. The employee record stays, inactive, for the history
 * (sales, invoices, expenses keep their author).
 */
@Service
public class RemoveEmployeeService implements RemoveEmployeeUseCase {

    static final String OWNER_REFUSED = "Le propriétaire du compte ne peut pas être supprimé";

    private final EmployeeRepository employeeRepository;
    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final DeactivateEmployeeUseCase deactivate;

    public RemoveEmployeeService(EmployeeRepository employeeRepository, UserMembershipRepository membershipRepository,
                                 TenantRepository tenantRepository, DeactivateEmployeeUseCase deactivate) {
        this.employeeRepository = employeeRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.deactivate = deactivate;
    }

    @Override
    @Transactional
    public void execute(UUID actorId, UUID employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
        String schema = TenantContext.requireCurrent();
        UUID tenantId = tenantRepository.findBySchemaName(schema).map(Tenant::getId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        boolean owner = membershipRepository.findByUserIdAndTenantId(employee.getUserId(), tenantId)
                .map(UserTenantMembership::getRole).map("OWNER"::equals).orElse(false);
        if (owner) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, OWNER_REFUSED);
        }
        if (employee.getStatus() != EmployeeStatus.INACTIVE) {
            deactivate.execute(new DeactivateEmployeeCommand(actorId, employeeId));
        }
        membershipRepository.remove(employee.getUserId(), tenantId);
    }
}
