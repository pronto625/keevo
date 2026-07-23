package com.keevo.identity.employee.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * EmployeeUpdatedEvent — Published after an owner updates an employee's profile
 * (first name, last name, phone number, store ID).
 *
 * <p>Story 14.11 — consumed by AuditEventListener for immutable audit trail.
 */
public record EmployeeUpdatedEvent(
        UUID actorId,
        String tenantId,
        UUID employeeId,
        List<String> fieldsChanged,
        Instant occurredAt
) {}
