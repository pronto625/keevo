package com.keevo.identity.api;

import java.util.UUID;

/**
 * Story 20.13 — changes of a member through {@link EmployeeCommandPort#updateMember}: a null field is kept;
 * {@code role} (MANAGER, EMPLOYEE, VIEWER, OWNER) goes through the role-change rules (last owner kept, sessions
 * revoked).
 */
public record UpdateMemberInput(UUID actorId, UUID employeeId, String firstName, String lastName, String phoneNumber,
                                UUID storeId, String role) {
}
