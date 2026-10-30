package com.keevo.shared.module;

import com.keevo.shared.module.application.ModuleCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Story 13.2 — a module catalogue for registry tests, independent of the shipped manifests: {@code core} (core),
 * {@code admin} (platform), {@code crm}/{@code pos}/{@code reporting}/{@code store} (business, free) and
 * {@code invoicing} (premium, paid).
 */
public final class TestModuleCatalogs {

    /** Free modules of {@link #testCatalog()}, sorted. */
    public static final List<String> FREE = List.of("admin", "core", "crm", "pos", "reporting", "store");
    /** Every module of {@link #testCatalog()}, sorted. */
    public static final List<String> ALL = List.of("admin", "core", "crm", "invoicing", "pos", "reporting", "store");

    private TestModuleCatalogs() {
    }

    public static ModuleCatalog testCatalog() {
        List<LoadedModuleManifest> manifests = new ArrayList<>();
        manifests.add(loaded("invoicing", ModuleCategory.PREMIUM, false)); // unsorted on purpose
        manifests.add(loaded("core", ModuleCategory.CORE, true));
        manifests.add(loaded("admin", ModuleCategory.PLATFORM, true));
        for (String id : List.of("store", "crm", "pos", "reporting")) {
            manifests.add(loaded(id, ModuleCategory.BUSINESS, true));
        }
        return new ModuleCatalog(manifests);
    }

    public static LoadedModuleManifest loaded(String id, ModuleCategory category, boolean free) {
        return loaded(id, category, free, List.of(), List.of());
    }

    /**
     * Story 13.3 — a manifest with extra hard dependencies ({@code core} is added for every non-core module) and
     * soft ones ({@code enhances}).
     */
    public static LoadedModuleManifest loaded(String id, ModuleCategory category, boolean free,
                                              List<String> requires, List<String> enhances) {
        List<String> all = new ArrayList<>();
        if (!id.equals(ModuleManifest.CORE_ID)) {
            all.add(ModuleManifest.CORE_ID);
        }
        all.addAll(requires);
        ModuleManifest manifest = new ModuleManifest(id, "1.0.0", id, category, all, enhances, List.of(),
                List.of(), List.of(), List.of(), null, List.of(), List.of(), "module." + id, free);
        return new LoadedModuleManifest("test:" + id, manifest, "{}");
    }

    /** Story 14.1 — a module manifest (requires {@code core}) declaring {@code permissions}. */
    public static LoadedModuleManifest withPermissions(String id, ModuleCategory category, boolean free,
                                                       List<String> permissions) {
        ModuleManifest manifest = new ModuleManifest(id, "1.0.0", id, category, List.of(ModuleManifest.CORE_ID),
                List.of(), List.of(), List.of(), List.of(), List.of(), null, permissions, List.of(), "module." + id,
                free);
        return new LoadedModuleManifest("test:" + id, manifest, "{}");
    }

    /**
     * Story 13.3 — a dependency graph for resolver tests (every module also requires {@code core}):
     * <ul>
     *   <li>{@code x} requires {@code catalog} + {@code inventory}; {@code inventory} requires {@code catalog};</li>
     *   <li>{@code alpha} → {@code beta} → {@code gamma} (multi-level);</li>
     *   <li>{@code buyer} requires the paid {@code paid};</li>
     *   <li>{@code helper} only enhances {@code beta} (no constraint);</li>
     *   <li>{@code solo} requires nothing but core;</li>
     *   <li>diamond: {@code top} requires {@code [zeta, eta]} (reverse order on purpose), both require {@code base}.</li>
     * </ul>
     * Every module is free except {@code paid}.
     */
    public static ModuleCatalog dependencyCatalog() {
        ModuleCategory b = ModuleCategory.BUSINESS;
        return new ModuleCatalog(List.of(
                loaded("x", b, true, List.of("inventory", "catalog"), List.of()), // unsorted on purpose
                loaded("core", ModuleCategory.CORE, true),
                loaded("inventory", b, true, List.of("catalog"), List.of()),
                loaded("catalog", b, true),
                loaded("alpha", b, true, List.of("beta"), List.of()),
                loaded("beta", b, true, List.of("gamma"), List.of()),
                loaded("gamma", b, true),
                loaded("paid", ModuleCategory.PREMIUM, false),
                loaded("buyer", b, true, List.of("paid"), List.of()),
                loaded("helper", b, true, List.of(), List.of("beta")),
                loaded("solo", b, true),
                loaded("top", b, true, List.of("zeta", "eta"), List.of()),
                loaded("zeta", b, true, List.of("base"), List.of()),
                loaded("eta", b, true, List.of("base"), List.of()),
                loaded("base", b, true)));
    }

    /**
     * Story 13.4 — modules of the provisioner tests (migrations under {@code src/test/resources/db/modules/<id>/},
     * "next release" scripts under {@code db/modules-next/<id>/}): {@code provtest} (V1+V2, next V3),
     * {@code provbroken} (V1, next V2 invalid), {@code provbatch} (V1), {@code provpaid} (V1, paid) and
     * {@code provempty} (no migrations), {@code provpartial} (V1+V2, next V3 invalid), {@code provmisnamed} (only a
     * misnamed file) and {@code provmixed} (V1 + a misnamed V2). All free except {@code provpaid}.
     */
    public static ModuleCatalog provisioningCatalog() {
        ModuleCategory b = ModuleCategory.BUSINESS;
        return new ModuleCatalog(List.of(
                loaded("core", ModuleCategory.CORE, true),
                loaded("provtest", b, true),
                loaded("provbroken", b, true),
                loaded("provbatch", b, true),
                loaded("provpaid", ModuleCategory.PREMIUM, false),
                loaded("provempty", b, true),
                loaded("provpartial", b, true),
                loaded("provmisnamed", b, true),
                loaded("provmixed", b, true)));
    }

    /**
     * Story 13.6 — a business module manifest (free, requires {@code core}) declaring capability {@code provides}
     * and {@code needs}.
     */
    public static LoadedModuleManifest withCapabilities(String id, List<String> provides, List<String> needs) {
        ModuleManifest manifest = new ModuleManifest(id, "1.0.0", id, ModuleCategory.BUSINESS,
                List.of(ModuleManifest.CORE_ID), List.of(), List.of(), List.of(), provides, needs, null, List.of(),
                List.of(), "module." + id, true);
        return new LoadedModuleManifest("test:" + id, manifest, "{}");
    }

    /**
     * Story 13.7 — a business module manifest (free, requires {@code core}) declaring and filling extension points.
     */
    public static LoadedModuleManifest withExtensionPoints(String id, List<String> declares, List<String> fills) {
        ModuleManifest manifest = new ModuleManifest(id, "1.0.0", id, ModuleCategory.BUSINESS,
                List.of(ModuleManifest.CORE_ID), List.of(), List.of(), List.of(), List.of(), List.of(),
                new ModuleManifest.ExtensionPoints(declares, fills), List.of(), List.of(), "module." + id, true);
        return new LoadedModuleManifest("test:" + id, manifest, "{}");
    }

    /**
     * Story 13.8a — modules of the lifecycle tests, with French display names (messages and audit name modules by
     * display name). Migrations under {@code src/test/resources/db/modules/<id>/}:
     * <ul>
     *   <li>{@code lcbase} « Base de test » (free, V1) ← {@code lctop} « Module supérieur » (free, V1) and
     *       {@code lcbroken} « Module cassé » (free, V1 invalid SQL) require it;</li>
     *   <li>{@code lcpaid} « Module payant » (premium, paid, V1) ← {@code lcbuyer} « Acheteur » (free) requires it;</li>
     *   <li>{@code lcplain} « Module simple » (free, no migrations), {@code lcsync} « Synchronisation » (category
     *       core, free), {@code lcadmin} « Plateforme » (platform).</li>
     * </ul>
     */
    public static ModuleCatalog lifecycleCatalog() {
        ModuleCategory b = ModuleCategory.BUSINESS;
        return new ModuleCatalog(List.of(
                named("core", "Socle", ModuleCategory.CORE, true, List.of()),
                named("lcbase", "Base de test", b, true, List.of()),
                named("lctop", "Module supérieur", b, true, List.of("lcbase")),
                named("lcbroken", "Module cassé", b, true, List.of("lcbase")),
                named("lcpaid", "Module payant", ModuleCategory.PREMIUM, false, List.of()),
                named("lcbuyer", "Acheteur", b, true, List.of("lcpaid")),
                named("lcplain", "Module simple", b, true, List.of()),
                named("lcsync", "Synchronisation", ModuleCategory.CORE, true, List.of()),
                named("lcadmin", "Plateforme", ModuleCategory.PLATFORM, true, List.of())));
    }

    /** Story 13.8a — a manifest with a display name ({@code core} added to the requires of non-core modules). */
    public static LoadedModuleManifest named(String id, String displayName, ModuleCategory category, boolean free,
                                             List<String> requires) {
        List<String> all = new ArrayList<>();
        if (!id.equals(ModuleManifest.CORE_ID)) {
            all.add(ModuleManifest.CORE_ID);
        }
        all.addAll(requires);
        ModuleManifest manifest = new ModuleManifest(id, "1.0.0", displayName, category, all, List.of(), List.of(),
                List.of(), List.of(), List.of(), null, List.of(), List.of(), "module." + id, free);
        return new LoadedModuleManifest("test:" + id, manifest, "{}");
    }
}
