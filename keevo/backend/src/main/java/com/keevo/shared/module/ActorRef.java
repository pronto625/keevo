package com.keevo.shared.module;

import java.util.UUID;

/**
 * Who triggers a module lifecycle change (Story 13.9): a user id (never shown to users) and whether it is a Keevo
 * super-admin.
 *
 * @param userId     the acting user, {@code null} for the platform itself
 * @param superAdmin whether the actor is a super-admin
 */
public record ActorRef(UUID userId, boolean superAdmin) {
}
