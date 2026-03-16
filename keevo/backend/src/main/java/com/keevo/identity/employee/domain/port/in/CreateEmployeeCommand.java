package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * CreateEmployeeCommand — Command record for employee creation.
 *
 * <p>Story 3.5 — AC1. MCP-ready: all inputs as record fields.
 */
public record CreateEmployeeCommand(
        UUID actorId,
        String phoneNumber,
        String firstName,
        String lastName,
        UUID storeId
) {}
