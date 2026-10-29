package com.keevo.identity.employee.adapter.in.api;

import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;

/** Maps the internal employee to its published view (Story 12.2d). */
final class EmployeeViews {

    private EmployeeViews() {}

    static EmployeeView of(Employee e) {
        return new EmployeeView(e.getId(), e.getUserId(), e.getStoreId(), e.getFirstName(), e.getLastName(),
                e.getStatus() == EmployeeStatus.ACTIVE);
    }
}
