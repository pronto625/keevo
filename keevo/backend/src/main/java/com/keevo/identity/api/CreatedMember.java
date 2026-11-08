package com.keevo.identity.api;

import java.util.UUID;

/**
 * Story 20.13 — a created member: its employee record, its user, the temporary password (shown once; null when an
 * existing account was attached) and whether an existing account was reused.
 */
public record CreatedMember(UUID employeeId, UUID userId, String temporaryPassword, boolean existingAccount) {
}
