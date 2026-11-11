package com.keevo.shared.infrastructure.security;

import com.keevo.shared.module.application.KeevoModuleDirectory;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModulePermissions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test helper (Story 19.5b) — an {@link ActorPermissions} over the SHIPPED manifests (the real role → permission
 * mapping), and a way to authenticate an actor with a role and a store like {@code JwtAuthFilter} does.
 */
public final class ShippedActorPermissions {

    private static ActorPermissions instance;
    private static ModulePermissions mapping;

    private ShippedActorPermissions() {
    }

    /** The evaluator over the shipped {@code module.json} manifests (built once). */
    public static synchronized ActorPermissions get() {
        if (instance == null) {
            ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
            ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.empty(catalog));
            mapping = permissions;
            permissions.afterSingletonsInstantiated();
            instance = new ActorPermissions(new StaticListableBeanFactory(Map.of("modulePermissions", permissions))
                    .getBeanProvider(ModulePermissions.class));
        }
        return instance;
    }

    /** Story 19.14 — the {@link ModulePermissions} over the shipped manifests (built once, shared with {@link #get()}). */
    public static synchronized ModulePermissions modulePermissions() {
        get();
        return mapping;
    }

    /**
     * Story 19.14 — authenticates a store member holding an owner-defined role granting {@code permissions}, as
     * {@code JwtAuthFilter} does.
     */
    public static void authenticateWithCustomRole(UUID actor, String role, UUID store, java.util.Set<String> permissions) {
        var token = new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        token.setDetails(new AuthDetails("Test", store, null,
                new com.keevo.shared.module.CustomRoleGrant(UUID.randomUUID(), "Caissier", permissions)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** Authenticates {@code actor} with {@code ROLE_<role>} and, when not {@code null}, the assigned {@code store}. */
    public static void authenticate(UUID actor, String role, UUID store) {
        var token = new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        token.setDetails(store == null ? AuthDetails.owner("Test") : AuthDetails.employee("Test", store));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
