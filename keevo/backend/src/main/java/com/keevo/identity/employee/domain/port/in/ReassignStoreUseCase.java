package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.employee.domain.model.Employee;

/**
 * ReassignStoreUseCase — Driving port for employee store reassignment.
 *
 * <p>Story 3.5 — AC6.
 */
public interface ReassignStoreUseCase {
    Employee execute(ReassignStoreCommand command);
}
