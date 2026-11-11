package com.keevo.identity.api;

import java.util.UUID;

/**
 * Story 20.13 — a created member: its employee record, its user, the temporary password (shown once) and whether an
 * existing account was reused.
 *
 * <p>Story 19.6b — {@code invitationPending}: the phone belongs to an existing account, which was invited (accepting
 * makes it a member); {@code employeeId}, {@code userId} and {@code temporaryPassword} are then null and
 * {@code existingAccount} true — nothing else about the account is disclosed.
 */
public record CreatedMember(UUID employeeId, UUID userId, String temporaryPassword, boolean existingAccount,
                            boolean invitationPending) {
}
