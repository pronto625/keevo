package com.keevo.shared.module;

import java.util.List;
import java.util.Set;

/**
 * Extension points declared by modules and the contributions usable per tenant (Story 13.7, spec-socle §8,
 * MOD-FR10).
 *
 * <p>A point is declared in its owner's manifest ({@code extensionPoints.declares}, id
 * {@code <owner>.<name>[.<name>…]}); contributions are {@link ContributesExtension} beans of other modules, listed in
 * their manifest's {@code extensionPoints.fills}. Registration is declarative only; a programmatic
 * {@code declare/contribute} may come with the {@code KeevoModule} lifecycle (13.9).
 *
 * <p>{@link #contributions} returns the contributions whose module is ENABLED for the tenant (they are actions: a
 * READ_ONLY module contributes nothing), and none while the declaring module is DISABLED (no slot for an inactive
 * module); sorted by {@link Extension#order()}, then module id, then {@link Extension#id()}; immutable, made of
 * {@link ExtensionView} snapshots (never the contribution beans). The
 * platform-admin schema ({@code "public"}) and an unknown tenant ({@link ModuleRegistry#isKnownTenant}) get an
 * empty list. Module states are read through
 * the registry's request cache; no transaction is opened.
 */
public interface ExtensionRegistry {

    /**
     * The contributions to {@code pointId} usable for the tenant.
     *
     * @throws IllegalArgumentException if no installed module declares {@code pointId}, or the schema is invalid
     */
    List<Extension> contributions(String pointId, String tenantSchema);

    /** Every extension point declared by an installed module (immutable, sorted). */
    Set<String> declaredPoints();
}
