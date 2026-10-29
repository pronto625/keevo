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
        List<String> requires = id.equals(ModuleManifest.CORE_ID) ? List.of() : List.of(ModuleManifest.CORE_ID);
        ModuleManifest manifest = new ModuleManifest(id, "1.0.0", id, category, requires, List.of(), List.of(),
                List.of(), List.of(), List.of(), null, List.of(), List.of(), "module." + id, free);
        return new LoadedModuleManifest("test:" + id, manifest, "{}");
    }
}
