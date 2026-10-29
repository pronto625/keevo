package com.keevo.shared.module;

import java.util.List;
import java.util.Objects;

/**
 * A validated module manifest ({@code keevo/modules/<id>/module.json}, spec-socle §3). Instances are only built by
 * {@link ModuleManifestLoader}, which guarantees the invariants documented on each component; every list is
 * immutable and never null.
 *
 * @param id              module id ({@link ModuleManifestLoader#ID_PATTERN}, ≤ 50 chars), equal to its manifest directory
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
        requires = List.copyOf(requires);
        enhances = List.copyOf(enhances);
        publishes = List.copyOf(publishes);
        consumes = List.copyOf(consumes);
        provides = List.copyOf(provides);
        needs = List.copyOf(needs);
        extensionPoints = extensionPoints == null ? ExtensionPoints.NONE : extensionPoints;
        permissions = List.copyOf(permissions);
        routes = List.copyOf(routes);
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
            declares = List.copyOf(declares);
            fills = List.copyOf(fills);
        }
    }
}
