package com.keevo.identity.employee.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeeRoleChangedEvent — Published after an owner changes an employee's role
 * (EMPLOYEE → OWNER or OWNER → EMPLOYEE).
 *
 * <p>Story 14.11 — consumed by AuditEventListener for immutable audit trail.
 */
public record EmployeeRoleChangedEvent(
        UUID actorId,
        String tenantId,
        UUID employeeId,
        String previousRole,
        String newRole,
        Instant occurredAt
) {}
