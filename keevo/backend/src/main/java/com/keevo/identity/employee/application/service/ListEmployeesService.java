package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ListEmployeesQuery;
import com.keevo.identity.employee.domain.port.in.ListEmployeesUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ListEmployeesService — Returns all employees for the current tenant (Story 3.5 AC8).
 */
@Service
public class ListEmployeesService implements ListEmployeesUseCase {

    private final EmployeeRepository employeeRepository;

    public ListEmployeesService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Employee> execute(ListEmployeesQuery query) {
        return employeeRepository.findAllOrderByStatusAndCreatedAt();
    }
}
