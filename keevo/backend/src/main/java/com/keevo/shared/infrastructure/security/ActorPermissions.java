package com.keevo.shared.infrastructure.security;

import com.keevo.identity.api.Role;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.application.ModulePermissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Set;

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

    private final ObjectProvider<ModulePermissions> permissions;

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
        Set<String> roles = ModulePermissions.rolesOf(authentication);
        if (roles.contains(Role.OWNER.name())) {
            return true;
        }
        ModulePermissions mapping = permissions == null ? null : permissions.getIfAvailable();
        return mapping != null && mapping.permissionsOf(roles).contains(permission);
    }

    /**
     * @throws DomainException FORBIDDEN (the shared French message) when the current actor lacks {@code permission}
     */
    public void requireCurrentActorHolds(String permission) {
        if (!currentActorHolds(permission)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Missing permission " + permission);
        }
    }
}
