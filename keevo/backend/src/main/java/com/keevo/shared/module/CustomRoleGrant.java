package com.keevo.shared.module;

import java.util.Set;
import java.util.UUID;

/**
 * Story 19.14 — the custom role (defined by the company owner) an authenticated store member holds, carried by the
 * authentication details ({@code AuthDetails}) and read by the single permission evaluator
 * {@code ModulePermissions#permissionsOf(org.springframework.security.core.Authentication)}.
 *
 * <p>Resolved once per request from the server-side access state (Story 16.11): {@code permissions} is already the
 * role's chosen set for this member — EMPTY when the role does not apply to him (store outside the role's stores, or a
 * base role that differs from his membership role — fail closed). A member without custom role carries no grant
 * ({@code null}) and keeps the built-in role's permissions.
 *
 * @param roleId      the role id
 * @param roleName    the role name the owner typed (« Caissier »)
 * @param permissions the permissions the role grants this member (never null)
 */
public record CustomRoleGrant(UUID roleId, String roleName, Set<String> permissions) {

    public CustomRoleGrant {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** Implemented by the authentication details that may carry a custom role. */
    public interface Carrier {

        /** The custom role of the actor, {@code null} when he holds none (built-in role permissions apply). */
        CustomRoleGrant customRole();
    }
}
