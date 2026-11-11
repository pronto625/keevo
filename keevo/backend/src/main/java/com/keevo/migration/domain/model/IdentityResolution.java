package com.keevo.migration.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.7 — what the identity bridge decided for one Bref account (owner or live member), before any write.
 *
 * @param keevoAccountId the existing Keevo account for {@link Action#LINK}, {@link Action#INVITE} and
 *                       {@link Action#SKIP}; {@code null} for {@link Action#CREATE} and {@link Action#BLOCK}
 * @param dropEmail      {@link Action#CREATE} only: the Bref email is held by another Keevo account without proof,
 *                       so the account is created without it
 * @param cases          {@link Action#BLOCK} only: why
 */
public record IdentityResolution(Action action, UUID keevoAccountId, boolean dropEmail, List<BlockedCase> cases) {

    public enum Action {
        /** A new Keevo account with the Bref id (24.2). */
        CREATE,
        /** The existing Keevo account is kept and attached (membership, employee record). */
        LINK,
        /** Member only: the existing Keevo account is invited (19.6b), the person accepts or declines. */
        INVITE,
        /** Member only: matches an existing Keevo account but is deactivated in Bref — not imported, not invited. */
        SKIP,
        /** The owner cannot be imported until the case is resolved by hand. */
        BLOCK
    }

    public IdentityResolution {
        Objects.requireNonNull(action, "action");
        cases = cases == null ? List.of() : List.copyOf(cases);
        if ((action == Action.LINK || action == Action.INVITE || action == Action.SKIP) != (keevoAccountId != null)) {
            throw new IllegalArgumentException("a Keevo account goes with LINK, INVITE and SKIP only");
        }
        if ((action == Action.BLOCK) == cases.isEmpty()) {
            throw new IllegalArgumentException("cases go with BLOCK only");
        }
        if (dropEmail && action != Action.CREATE) {
            throw new IllegalArgumentException("dropEmail goes with CREATE only");
        }
    }

    public static IdentityResolution create(boolean dropEmail) {
        return new IdentityResolution(Action.CREATE, null, dropEmail, List.of());
    }

    public static IdentityResolution link(UUID keevoAccountId) {
        return new IdentityResolution(Action.LINK, keevoAccountId, false, List.of());
    }

    public static IdentityResolution invite(UUID keevoAccountId) {
        return new IdentityResolution(Action.INVITE, keevoAccountId, false, List.of());
    }

    public static IdentityResolution skip(UUID keevoAccountId) {
        return new IdentityResolution(Action.SKIP, keevoAccountId, false, List.of());
    }

    public static IdentityResolution block(List<BlockedCase> cases) {
        return new IdentityResolution(Action.BLOCK, null, false, cases);
    }

    /** Whether a Keevo account other than a new one is used (link, invitation). */
    public boolean existingAccount() {
        return action == Action.LINK || action == Action.INVITE;
    }
}
