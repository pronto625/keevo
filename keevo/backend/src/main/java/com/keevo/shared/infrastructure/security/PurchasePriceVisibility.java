package com.keevo.shared.infrastructure.security;

import com.keevo.identity.api.Role;
import com.keevo.shared.module.application.ModulePermissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Story 16.13 (reprise 10/11/2026, user decision D2) — THE single policy deciding who may see purchase prices
 * ({@code buyPrice}, {@code transportCost} and every cost / margin derived from them).
 *
 * <p>The decision is the module permission {@value #PERMISSION}, declared by the {@code catalog} manifest
 * ({@code keevo/modules/catalog/module.json}): the {@code OWNER} holds it (OWNER holds every declared permission) and
 * the {@code MANAGER} (Bref « admin ») through its {@code rolePermissions}; {@code EMPLOYEE} and {@code VIEWER} do not.
 * REST controllers, AI tools and the write guards ask this component; the sync pull asks
 * {@code PullScope.holds(PurchasePriceVisibility.PERMISSION)}, resolved by the pull endpoint from the same
 * {@link ModulePermissions} mapping.
 *
 * <p><b>Hook for story 19-14</b> (owner-defined roles with chosen permissions): only the role → permission mapping
 * changes (per-tenant roles instead of the manifest's {@code rolePermissions}); {@value #PERMISSION} becomes a
 * permission the owner ticks (« Voir les prix d'achat ») and no call site of this class changes.
 */
@Component
public class PurchasePriceVisibility {

    /** The permission guarding purchase prices (declared by the {@code catalog} module manifest). */
    public static final String PERMISSION = "PURCHASE_PRICE_VIEW";

    private final ObjectProvider<ModulePermissions> permissions;

    public PurchasePriceVisibility(ObjectProvider<ModulePermissions> permissions) {
        this.permissions = permissions;
    }

    /**
     * The policy without any role mapping (constructions outside Spring, e.g. a use case built by hand): only the
     * OWNER sees purchase prices — fail closed for every other role.
     */
    public static PurchasePriceVisibility withoutRoleMapping() {
        return new PurchasePriceVisibility(null);
    }

    /** Whether the authenticated actor may see purchase prices; {@code false} when unauthenticated (fail closed). */
    public boolean currentActorCanView() {
        return canView(CurrentActor.authentication());
    }

    /** Whether this authentication may see purchase prices; {@code false} for {@code null} (fail closed). */
    public boolean canView(Authentication authentication) {
        return authentication != null && canView(ModulePermissions.rolesOf(authentication));
    }

    /**
     * Whether a user with these roles (names without the {@code ROLE_} prefix) may see purchase prices: the OWNER
     * always; anyone else when the role mapping grants {@value #PERMISSION} (fail closed when the mapping is absent).
     */
    public boolean canView(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        if (roles.contains(Role.OWNER.name())) {
            return true;
        }
        ModulePermissions mapping = permissions == null ? null : permissions.getIfAvailable();
        return mapping != null && mapping.permissionsOf(roles).contains(PERMISSION);
    }
}
