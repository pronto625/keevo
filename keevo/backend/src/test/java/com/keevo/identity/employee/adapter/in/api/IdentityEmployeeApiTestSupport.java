package com.keevo.identity.employee.adapter.in.api;

import com.keevo.identity.api.EmployeeCommandPort;
import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReassignStoreUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;

/**
 * Test-only access to the package-private employee adapters of {@code identity :: api}
 * (Story 12.2d), for tests of other packages that drive them over mocked repositories / use cases.
 */
public final class IdentityEmployeeApiTestSupport {

    private IdentityEmployeeApiTestSupport() {}

    public static EmployeeQueryPort queryPort(EmployeeRepository employeeRepository) {
        return new EmployeeQueryAdapter(employeeRepository);
    }

    public static EmployeeCommandPort commandPort(CreateEmployeeUseCase create, ReassignStoreUseCase reassign,
                                                  DeactivateEmployeeUseCase deactivate,
                                                  ReactivateEmployeeUseCase reactivate) {
        return new EmployeeCommandAdapter(create, reassign, deactivate, reactivate);
    }

    public static EmployeeView view(Employee employee) {
        return EmployeeViews.of(employee);
    }
}
