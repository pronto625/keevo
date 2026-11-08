package com.keevo.identity.api;

import java.util.UUID;

/**
 * Story 20.13 — a member to add to an account through {@link EmployeeCommandPort#createMember} (super-admin API):
 * same rules as the owner's creation (E.164 phone, plan limits, temporary password, existing account reused);
 * {@code role} null = {@code EMPLOYEE}, else {@code MANAGER}, {@code EMPLOYEE} or {@code VIEWER}.
 */
public record CreateMemberInput(UUID actorId, String phoneNumber, String firstName, String lastName, UUID storeId,
                                String role) {
}
