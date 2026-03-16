package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReactivateEmployeeService — Restores a previously deactivated employee.
 *
 * <p>Sets employee status back to ACTIVE and reactivates the user-tenant membership.
 */
@Service
public class ReactivateEmployeeService implements ReactivateEmployeeUseCase {

    private final EmployeeRepository employeeRepository;
    private final UserMembershipRepository membershipRepository;

    public ReactivateEmployeeService(EmployeeRepository employeeRepository,
                                      UserMembershipRepository membershipRepository) {
        this.employeeRepository = employeeRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional
    public void execute(ReactivateEmployeeCommand command) {
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // Set status ACTIVE
        employeeRepository.updateStatus(command.employeeId(), EmployeeStatus.ACTIVE);

        // Reactivate membership
        membershipRepository.reactivateByUserId(employee.getUserId());
    }
}
