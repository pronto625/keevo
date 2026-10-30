package com.keevo.shared.module.application;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleManifest;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Story 14.1 — the permissions of the installed modules and their role mapping (spec-socle §9.2):
 * <ul>
 *   <li>{@code OWNER}: every permission declared by an installed manifest ({@code permissions});</li>
 *   <li>{@code EMPLOYEE}: the union of the modules' {@link KeevoModule#employeePermissions()};</li>
 *   <li>{@code SUPER_ADMIN} (or any other role): none — the platform administrator is not a tenant user.</li>
 * </ul>
 * Validated at boot (fail-fast, one {@link IllegalStateException}): a module's employee permissions must be declared
 * by its own manifest, and a permission may be declared by one manifest only.
 */
@Service
@Lazy(false)
public class ModulePermissions implements SmartInitializingSingleton {

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_EMPLOYEE = "EMPLOYEE";

    private final ModuleCatalog catalog;
    private final KeevoModuleDirectory modules;
    private volatile Mapping mapping;

    public ModulePermissions(ModuleCatalog catalog, KeevoModuleDirectory modules) {
        this.catalog = catalog;
        this.modules = modules;
    }

    private record Mapping(Set<String> declared, Set<String> employee) {
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

    /** The permissions held by a user with these roles (role names without the {@code ROLE_} prefix). */
    public Set<String> permissionsOf(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Mapping m = mapping();
        if (roles.contains(ROLE_OWNER)) {
            return m.declared();
        }
        if (roles.contains(ROLE_EMPLOYEE)) {
            return m.employee();
        }
        return Set.of();
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
        for (ModuleManifest manifest : catalog.manifests()) {
            for (String permission : manifest.permissions()) {
                String previous = owner.putIfAbsent(permission, manifest.id());
                if (previous != null && !previous.equals(manifest.id())) {
                    problems.add("permission '" + permission + "' is declared by modules '" + previous + "' and '"
                            + manifest.id() + "'");
                }
            }
        }
        Set<String> employee = new TreeSet<>();
        for (KeevoModule module : modules.all()) {
            Set<String> granted = module.employeePermissions();
            if (granted == null) {
                continue;
            }
            List<String> own = catalog.find(module.id()).map(ModuleManifest::permissions).orElse(List.of());
            for (String permission : granted) {
                if (!own.contains(permission)) {
                    problems.add("module '" + module.id() + "' grants '" + permission + "' to EMPLOYEE but its "
                            + "manifest does not declare it");
                } else {
                    employee.add(permission);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid module permissions: " + String.join("; ", problems));
        }
        return new Mapping(Collections.unmodifiableSet(new TreeSet<>(owner.keySet())),
                Collections.unmodifiableSet(employee));
    }
}
