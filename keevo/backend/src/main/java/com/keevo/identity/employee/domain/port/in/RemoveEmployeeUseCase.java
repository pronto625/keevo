package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/** Story 20.13 — removes a member from the current account (never its owner). */
public interface RemoveEmployeeUseCase {

    void execute(UUID actorId, UUID employeeId);
}
