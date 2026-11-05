package com.keevo.shared.module;

import com.keevo.identity.api.Role;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A validated module manifest ({@code keevo/modules/<id>/module.json}, spec-socle §3). Production instances are only
 * built by {@link ModuleManifestLoader}, which guarantees the invariants documented on each component (cross-file
 * rules included). Since Story 13.9 the compact constructor enforces the per-instance invariants itself, so a
 * hand-built instance (tests, tooling) cannot be invalid either: {@code id} matches
 * {@link ModuleManifestLoader#ID_PATTERN} within {@link ModuleManifestLoader#ID_MAX} characters, {@code version},
 * {@code category} and {@code entitlement} are present, {@code displayName} is not blank, every list (and every list
 * entry) is non-null and the lists are immutable copies — otherwise {@link IllegalArgumentException} (or
 * {@link NullPointerException} for a missing component).
 *
 * @param id              module id ({@link ModuleManifestLoader#ID_PATTERN}, ≤ {@value ModuleManifestLoader#ID_MAX}
 *                        chars), equal to its manifest directory
 * @param version         semver {@code MAJOR.MINOR.PATCH}
 * @param displayName     French label shown to users
 * @param category        module category
 * @param requires        hard dependencies (known module ids, acyclic; every non-core module requires {@code core})
 * @param enhances        soft dependencies (may name modules that are not installed)
 * @param publishes       event types published
 * @param consumes        event types consumed
 * @param provides        capability ports provided
 * @param needs           capability ports needed ({@code ?} suffix = optional)
 * @param extensionPoints UI/workflow slots declared and filled
 * @param permissions     permissions contributed
 * @param routes          client routes contributed
 * @param entitlement     entitlement key ({@code module.<id>} by default)
 * @param free            part of the free offer
 * @param rolePermissions Story 19.5 — the permissions granted per membership role ({@code MANAGER}, {@code EMPLOYEE},
 *                        {@code VIEWER}; {@code OWNER} implicitly holds every permission): an immutable sorted copy,
 *                        empty when the manifest declares none. Completeness and consistency are validated at boot by
 *                        {@code ModulePermissions}.
 * @param ownerToggleable Story 19.13 — whether the tenant's owner may disable the module in « Mes modules » (manifest
 *                        {@code ownerToggleable}, default {@code true}); never for {@code core}/{@code platform}
 *                        categories, whatever the manifest says ({@link #ownerCanToggle()})
 */
public record ModuleManifest(
        String id,
        String version,
        String displayName,
        ModuleCategory category,
        List<String> requires,
        List<String> enhances,
        List<String> publishes,
        List<String> consumes,
        List<String> provides,
        List<String> needs,
        ExtensionPoints extensionPoints,
        List<String> permissions,
        List<String> routes,
        String entitlement,
        boolean free,
        Map<String, List<String>> rolePermissions,
        boolean ownerToggleable) {

    /** Story 19.5 — the membership roles every manifest must declare in {@code rolePermissions}. */
    public static final List<String> DECLARED_ROLES = Role.STORE_MEMBER_NAMES;

    /** Id of the mandatory core module, required by every other module. */
    public static final String CORE_ID = "core";

    public ModuleManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(entitlement, "entitlement");
        if (id.length() > ModuleManifestLoader.ID_MAX || !ModuleManifestLoader.ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid module id '" + id + "': it must match "
                    + ModuleManifestLoader.ID_PATTERN.pattern() + " (at most " + ModuleManifestLoader.ID_MAX
                    + " characters)");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Module '" + id + "': the display name must not be blank");
        }
        requires = immutable(requires, "requires", id);
        enhances = immutable(enhances, "enhances", id);
        publishes = immutable(publishes, "publishes", id);
        consumes = immutable(consumes, "consumes", id);
        provides = immutable(provides, "provides", id);
        needs = immutable(needs, "needs", id);
        extensionPoints = extensionPoints == null ? ExtensionPoints.NONE : extensionPoints;
        permissions = immutable(permissions, "permissions", id);
        routes = immutable(routes, "routes", id);
        TreeMap<String, List<String>> byRole = new TreeMap<>();
        if (rolePermissions != null) {
            for (Map.Entry<String, List<String>> entry : rolePermissions.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("Module '" + id + "': 'rolePermissions' has a null role");
                }
                byRole.put(entry.getKey(), immutable(entry.getValue(), "rolePermissions." + entry.getKey(), id));
            }
        }
        rolePermissions = Collections.unmodifiableMap(byRole);
    }

    /** Pre-19.13 arity — {@code ownerToggleable = true} (hand-built manifests: tests, tooling). */
    public ModuleManifest(String id, String version, String displayName, ModuleCategory category,
                          List<String> requires, List<String> enhances, List<String> publishes,
                          List<String> consumes, List<String> provides, List<String> needs,
                          ExtensionPoints extensionPoints, List<String> permissions, List<String> routes,
                          String entitlement, boolean free, Map<String, List<String>> rolePermissions) {
        this(id, version, displayName, category, requires, enhances, publishes, consumes, provides, needs,
                extensionPoints, permissions, routes, entitlement, free, rolePermissions, true);
    }

    /**
     * A manifest whose {@code rolePermissions} grants nothing to {@code MANAGER}, {@code EMPLOYEE} and {@code VIEWER}
     * (explicit empty entries) — hand-built manifests (tests, tooling).
     */
    public ModuleManifest(String id, String version, String displayName, ModuleCategory category,
                          List<String> requires, List<String> enhances, List<String> publishes,
                          List<String> consumes, List<String> provides, List<String> needs,
                          ExtensionPoints extensionPoints, List<String> permissions, List<String> routes,
                          String entitlement, boolean free) {
        this(id, version, displayName, category, requires, enhances, publishes, consumes, provides, needs,
                extensionPoints, permissions, routes, entitlement, free, noRolePermissions());
    }

    /** Explicit empty entries for every {@link #DECLARED_ROLES declared role}. */
    public static Map<String, List<String>> noRolePermissions() {
        TreeMap<String, List<String>> none = new TreeMap<>();
        DECLARED_ROLES.forEach(role -> none.put(role, List.of()));
        return none;
    }

    /** An immutable copy; a null list or entry → {@link IllegalArgumentException} naming the field. */
    private static List<String> immutable(List<String> list, String field, String id) {
        if (list == null || list.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Module '" + id + "': '" + field
                    + "' must be a list without null entries");
        }
        return List.copyOf(list);
    }

    /** Whether this is the core module. */
    public boolean isCore() {
        return CORE_ID.equals(id);
    }

    /**
     * Story 19.13 — whether the owner may activate / disable this module for the tenant: the manifest allows it and
     * the module is neither core nor of category {@code core} / {@code platform}.
     */
    public boolean ownerCanToggle() {
        return ownerToggleable && !isCore() && category != ModuleCategory.CORE && category != ModuleCategory.PLATFORM;
    }

    /**
     * Extension points of a module (spec-socle §8).
     *
     * @param declares slots this module declares
     * @param fills    slots of other modules this module fills
     */
    public record ExtensionPoints(List<String> declares, List<String> fills) {

        public static final ExtensionPoints NONE = new ExtensionPoints(List.of(), List.of());

        public ExtensionPoints {
            if (declares == null || fills == null || declares.stream().anyMatch(Objects::isNull)
                    || fills.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("extensionPoints: 'declares' and 'fills' must be lists without "
                        + "null entries");
            }
            declares = List.copyOf(declares);
            fills = List.copyOf(fills);
        }
    }
}
