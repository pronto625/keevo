package com.keevo.identity.employee.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeeStoreReassignedEvent — Published after an employee is reassigned to a different store.
 *
 * <p>Story 3.5 — AC6.
 */
public record EmployeeStoreReassignedEvent(
        UUID actorId,
        UUID employeeId,
        UUID oldStoreId,
        UUID newStoreId,
        Instant occurredAt
) {}
