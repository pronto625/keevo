package com.keevo.shared.module;

import java.util.List;
import java.util.Objects;

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
        boolean free) {

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
