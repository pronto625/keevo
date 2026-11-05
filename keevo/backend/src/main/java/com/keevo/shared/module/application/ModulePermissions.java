package com.keevo.shared.module.application;

import com.keevo.identity.api.Role;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleManifest;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Story 14.1 / 19.5 — the permissions of the installed modules and their role mapping (spec-socle §9.2):
 * <ul>
 *   <li>{@code OWNER}: every permission declared by an installed manifest ({@code permissions});</li>
 *   <li>{@code MANAGER}, {@code EMPLOYEE}, {@code VIEWER}: the union of the manifests' {@code rolePermissions} entry
 *       for that role (Story 19.5, cross-checked with {@link KeevoModule#permissionsFor(Role)});</li>
 *   <li>{@code SUPER_ADMIN} (or any other role): none — the platform administrator is not a tenant user.</li>
 * </ul>
 * Validated at boot (fail-fast, one {@link IllegalStateException}): a permission may be declared by one manifest only;
 * every manifest declares an explicit entry (an empty array allowed) for each of {@code MANAGER}, {@code EMPLOYEE},
 * {@code VIEWER} and no other role; a role lists only permissions of its own module; a {@code VIEWER} entry holds
 * only read permissions (ending in {@value #READ_SUFFIX}); a module bean's code-side declaration, when it gives one,
 * equals its manifest's. Cost O(modules × roles), once.
 */
@Service
@Lazy(false)
public class ModulePermissions implements SmartInitializingSingleton {

    public static final String ROLE_OWNER = Role.OWNER.name();

    /** Story 19.5 — a VIEWER may only hold permissions ending with this suffix (read permissions). */
    public static final String READ_SUFFIX = "_VIEW";

    private static final String ROLE_PREFIX = "ROLE_";

    /** The roles whose permissions are declared per manifest ({@code OWNER} holds them all implicitly). */
    private static final List<Role> DECLARED_ROLES = Role.STORE_MEMBERS;

    private final ModuleCatalog catalog;
    private final KeevoModuleDirectory modules;
    private volatile Mapping mapping;

    public ModulePermissions(ModuleCatalog catalog, KeevoModuleDirectory modules) {
        this.catalog = catalog;
        this.modules = modules;
    }

    private record Mapping(Set<String> declared, Map<Role, Set<String>> byRole) {
    }

    @Override
    public void afterSingletonsInstantiated() {
        mapping();
    }

    /** Every permission declared by an installed manifest. */
    public Set<String> declared() {
        return mapping().declared();
    }

    /** Whether {@code permission} is declared by an installed manifest. */
    public boolean isDeclared(String permission) {
        return permission != null && mapping().declared().contains(permission);
    }

    /**
     * Story 14.5 — the role names ({@code ROLE_<role>} authorities without the prefix) of an authentication, as
     * {@link #permissionsOf} expects them; empty for {@code null}. Shared by the {@code @RequiresPermission} check and
     * the extension slot filter so both always agree.
     */
    public static Set<String> rolesOf(Authentication authentication) {
        Set<String> roles = new HashSet<>();
        if (authentication != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                String name = authority.getAuthority();
                if (name != null && name.startsWith(ROLE_PREFIX)) {
                    roles.add(name.substring(ROLE_PREFIX.length()));
                }
            }
        }
        return roles;
    }

    /** The permissions held by one role: all for {@code OWNER}, the declared ones for the others, none otherwise. */
    public Set<String> permissionsFor(Role role) {
        if (role == null) {
            return Set.of();
        }
        Mapping m = mapping();
        if (role == Role.OWNER) {
            return m.declared();
        }
        return m.byRole().getOrDefault(role, Set.of());
    }

    /** The permissions held by a user with these roles (role names without the {@code ROLE_} prefix). */
    public Set<String> permissionsOf(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        if (roles.contains(ROLE_OWNER)) {
            return mapping().declared();
        }
        if (roles.size() == 1) {
            return permissionsFor(Role.parse(roles.iterator().next())); // the cached immutable set, no copy
        }
        Set<String> held = new TreeSet<>();
        for (String name : roles) {
            held.addAll(permissionsFor(Role.parse(name)));
        }
        return Collections.unmodifiableSet(held);
    }

    private Mapping mapping() {
        Mapping m = mapping;
        if (m == null) {
            synchronized (this) {
                if (mapping == null) {
                    mapping = build();
                }
                m = mapping;
            }
        }
        return m;
    }

    private Mapping build() {
        List<String> problems = new ArrayList<>();
        TreeMap<String, String> owner = new TreeMap<>();
        Map<Role, Set<String>> byRole = new EnumMap<>(Role.class);
        DECLARED_ROLES.forEach(role -> byRole.put(role, new TreeSet<>()));
        for (ModuleManifest manifest : catalog.manifests()) {
            for (String permission : manifest.permissions()) {
                String previous = owner.putIfAbsent(permission, manifest.id());
                if (previous != null && !previous.equals(manifest.id())) {
                    problems.add("permission '" + permission + "' is declared by modules '" + previous + "' and '"
                            + manifest.id() + "'");
                }
            }
            Map<String, List<String>> declaredByRole = manifest.rolePermissions();
            for (String role : declaredByRole.keySet()) {
                Role parsed = Role.parse(role);
                if (parsed == null || !DECLARED_ROLES.contains(parsed)) {
                    problems.add("module '" + manifest.id() + "' declares rolePermissions for '" + role
                            + "' (only MANAGER, EMPLOYEE and VIEWER are declared; OWNER holds every permission)");
                }
            }
            for (Role role : DECLARED_ROLES) {
                List<String> granted = declaredByRole.get(role.name());
                if (granted == null) {
                    problems.add("module '" + manifest.id() + "' has no explicit rolePermissions entry for "
                            + role.name() + " (an empty array grants nothing)");
                    continue;
                }
                for (String permission : granted) {
                    if (!manifest.permissions().contains(permission)) {
                        problems.add("module '" + manifest.id() + "' grants '" + permission + "' to " + role.name()
                                + " but its manifest does not declare it");
                    } else if (role == Role.VIEWER && !permission.endsWith(READ_SUFFIX)) {
                        problems.add("module '" + manifest.id() + "' grants the write permission '" + permission
                                + "' to VIEWER (a VIEWER never writes)");
                    } else {
                        byRole.get(role).add(permission);
                    }
                }
            }
        }
        for (KeevoModule module : modules.all()) {
            ModuleManifest manifest = catalog.find(module.id()).orElse(null);
            if (manifest == null) {
                continue;
            }
            for (Role role : DECLARED_ROLES) {
                Set<String> code = module.permissionsFor(role);
                if (code == null) {
                    continue;
                }
                Set<String> declared = new TreeSet<>(manifest.rolePermissions().getOrDefault(role.name(), List.of()));
                if (!declared.equals(new TreeSet<>(code))) {
                    problems.add("module '" + module.id() + "' grants " + new TreeSet<>(code) + " to " + role.name()
                            + " in code but " + declared + " in its manifest");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid module permissions: " + String.join("; ", problems));
        }
        Map<Role, Set<String>> frozen = new EnumMap<>(Role.class);
        byRole.forEach((role, set) -> frozen.put(role, Collections.unmodifiableSet(set)));
        return new Mapping(Collections.unmodifiableSet(new TreeSet<>(owner.keySet())),
                Collections.unmodifiableMap(frozen));
    }
}
