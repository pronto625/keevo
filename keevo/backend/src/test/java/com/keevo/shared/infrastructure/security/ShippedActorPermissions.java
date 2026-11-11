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

    private ShippedActorPermissions() {
    }

    /** The evaluator over the shipped {@code module.json} manifests (built once). */
    public static synchronized ActorPermissions get() {
        if (instance == null) {
            ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
            ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.empty(catalog));
            permissions.afterSingletonsInstantiated();
            instance = new ActorPermissions(new StaticListableBeanFactory(Map.of("modulePermissions", permissions))
                    .getBeanProvider(ModulePermissions.class));
        }
        return instance;
    }

    /** Authenticates {@code actor} with {@code ROLE_<role>} and, when not {@code null}, the assigned {@code store}. */
    public static void authenticate(UUID actor, String role, UUID store) {
        var token = new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        token.setDetails(store == null ? AuthDetails.owner("Test") : AuthDetails.employee("Test", store));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
