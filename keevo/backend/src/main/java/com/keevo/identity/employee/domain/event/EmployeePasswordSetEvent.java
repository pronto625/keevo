package com.keevo.identity.employee.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeePasswordSetEvent — Published after employee changes their temporary password.
 *
 * <p>Story 3.5 — AC4.
 */
public record EmployeePasswordSetEvent(
        UUID actorId,
        UUID employeeId,
        Instant occurredAt
) {}
