package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * ListEmployeesQuery — Query record for listing all employees.
 *
 * <p>Story 3.5 — AC8.
 */
public record ListEmployeesQuery(UUID actorId) {}
