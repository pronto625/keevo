package com.keevo.shared.domain;

import com.keevo.identity.api.Role;

/**
 * Story 19.5 — role-name decisions for the modules that work with the actor's role as a string and may not depend on
 * {@code identity :: api} (catalog, inventory…). Delegates to {@link Role}, the single source of the rule.
 *
 * <p>Pure Java — no framework imports.
 */
public final class MemberRoles {

    private MemberRoles() {
    }

    /**
     * Whether the role is pinned to one store and restricted like an {@code EMPLOYEE}: {@code EMPLOYEE},
     * {@code MANAGER}, {@code VIEWER}. Use it wherever « non-owners » are restricted.
     */
    public static boolean isStoreMember(String role) {
        return Role.isStoreMember(role);
    }
}
