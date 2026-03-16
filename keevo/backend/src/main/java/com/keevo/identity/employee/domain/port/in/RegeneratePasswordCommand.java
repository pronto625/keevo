package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * RegeneratePasswordCommand — Regenerates the temporary password for an employee.
 */
public record RegeneratePasswordCommand(
        UUID actorId,
        UUID employeeId
) {}
