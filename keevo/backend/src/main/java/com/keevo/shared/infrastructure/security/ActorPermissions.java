package com.keevo.shared.infrastructure.security;

import com.keevo.identity.api.Role;
import com.keevo.shared.module.application.ModulePermissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;


/**
 * Story 19.5b — THE single evaluator of « does the authenticated actor hold module permission P » for the rules that
 * are not a whole endpoint ({@code @RequiresPermission} covers those): store-wide sales, inventory sessions of the
 * store, team management, cancelling a document. Permissions are declared by the module manifests
 * ({@code module.json} {@code permissions} + {@code rolePermissions}) and mapped to roles by {@link ModulePermissions};
 * the {@code OWNER} holds every declared permission.
 *
 * <p><b>Hook for story 19-14</b> (owner-defined roles): only the role → permission mapping changes; the call sites
 * keep asking this component with the same permission names. Fails closed: no authentication, or no mapping bean,
 * means no permission (the OWNER still holds all).
 */
@Component
public class ActorPermissions {

    private static final String OWNER_AUTHORITY = "ROLE_" + Role.OWNER.name();

    private final ObjectProvider<ModulePermissions> permissions;
    private volatile ModulePermissions resolved;

    public ActorPermissions(ObjectProvider<ModulePermissions> permissions) {
        this.permissions = permissions;
    }

    /** Whether the authenticated actor holds {@code permission}; {@code false} when unauthenticated (fail closed). */
    public boolean currentActorHolds(String permission) {
        return holds(CurrentActor.authentication(), permission);
    }

    /** Whether this authentication holds {@code permission}; {@code false} for {@code null} (fail closed). */
    public boolean holds(Authentication authentication, String permission) {
        if (authentication == null || permission == null) {
            return false;
        }
        boolean owner = authentication.getAuthorities().stream()
                .anyMatch(a -> OWNER_AUTHORITY.equals(a.getAuthority()));
        if (owner) {
            return true; // no role set built: the OWNER holds every declared permission
        }
        ModulePermissions mapping = mapping();
        return mapping != null && mapping.permissionsOf(ModulePermissions.rolesOf(authentication)).contains(permission);
    }

    /** Whether {@code permission} is declared by an installed manifest (pins the call sites' constants). */
    public boolean isDeclared(String permission) {
        ModulePermissions mapping = mapping();
        return mapping != null && mapping.isDeclared(permission);
    }

    private ModulePermissions mapping() {
        ModulePermissions m = resolved;
        if (m == null && permissions != null) {
            m = permissions.getIfAvailable();
            resolved = m;
        }
        return m;
    }
}
