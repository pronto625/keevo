package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ReactivateEmployeeService — Restores a previously deactivated employee.
 *
 * <p>Sets employee status back to ACTIVE and reactivates the user-tenant membership.
 */
@Service
public class ReactivateEmployeeService implements ReactivateEmployeeUseCase {

    private final EmployeeRepository employeeRepository;
    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;

    public ReactivateEmployeeService(EmployeeRepository employeeRepository,
                                      UserMembershipRepository membershipRepository,
                                      TenantRepository tenantRepository) {
        this.employeeRepository = employeeRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional
    public void execute(ReactivateEmployeeCommand command) {
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // Reactivate the membership of THIS tenant only (v1s-13-9 follow-up: a membership the
        // user holds in another shop — possibly deactivated there — must stay untouched).
        String currentSchema = TenantContext.getCurrentTenant();
        if (currentSchema == null) {
            throw new IllegalStateException(
                    "TenantContext required to reactivate employee " + employee.getUserId());
        }
        UUID tenantId = tenantRepository.findBySchemaName(currentSchema)
                .map(Tenant::getId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));

        // Set status ACTIVE
        employeeRepository.updateStatus(command.employeeId(), EmployeeStatus.ACTIVE);

        membershipRepository.reactivate(employee.getUserId(), tenantId);
    }
}
