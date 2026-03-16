package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * ChangePasswordCommand — Command record for forced password change.
 *
 * <p>Story 3.5 — AC4. MCP-ready.
 */
public record ChangePasswordCommand(
        UUID actorId,
        String currentPassword,
        String newPassword
) {}
