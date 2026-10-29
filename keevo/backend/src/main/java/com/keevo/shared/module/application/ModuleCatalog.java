package com.keevo.shared.module.application;

import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The validated module manifest set of this build (Story 13.2, routed from 13.1): loaded and validated once at
 * startup through {@link ModuleManifestLoader} and the application context's resource resolver, then shared by the
 * boot seed ({@link ModuleCatalogSeeder}), the {@code ModuleRegistry} and later the dependency resolver — nobody
 * re-scans the classpath.
 *
 * <p>An invalid manifest set throws {@link com.keevo.shared.module.InvalidModuleManifestException} from the
 * constructor, so the context (and the boot) fails before anything reads or seeds the catalogue. Immutable,
 * thread-safe.
 */
@Component
public class ModuleCatalog {

    private final List<LoadedModuleManifest> loaded;
    private final List<ModuleManifest> manifests;
    private final Map<String, ModuleManifest> byId;

    @Autowired
    public ModuleCatalog(ResourcePatternResolver resources) {
        this(ModuleManifestLoader.loadFromClasspath(resources));
    }

    /**
     * Catalogue of already validated manifests (tests, tooling). Sorted by id; ids must be unique and exactly one
     * must be {@code core}.
     */
    public ModuleCatalog(List<LoadedModuleManifest> loaded) {
        List<LoadedModuleManifest> sorted = new ArrayList<>(loaded);
        sorted.sort(Comparator.comparing(m -> m.manifest().id()));
        this.loaded = List.copyOf(sorted);
        this.manifests = this.loaded.stream().map(LoadedModuleManifest::manifest).toList();
        Map<String, ModuleManifest> index = new LinkedHashMap<>();
        for (ModuleManifest manifest : manifests) {
            if (index.putIfAbsent(manifest.id(), manifest) != null) {
                throw new IllegalArgumentException("Duplicate module id in the catalogue: " + manifest.id());
            }
        }
        if (!index.containsKey(ModuleManifest.CORE_ID)) {
            throw new IllegalArgumentException("The catalogue must contain the module 'core'");
        }
        this.byId = Map.copyOf(index);
    }

    /** The validated manifests with their location and stored JSON, sorted by id. */
    public List<LoadedModuleManifest> loaded() {
        return loaded;
    }

    /** The installed modules (manifests on the classpath), sorted by id. */
    public List<ModuleManifest> manifests() {
        return manifests;
    }

    /** The manifest of {@code moduleId}, if that module is installed. */
    public Optional<ModuleManifest> find(String moduleId) {
        return moduleId == null ? Optional.empty() : Optional.ofNullable(byId.get(moduleId));
    }

    /** Whether {@code moduleId} is installed. */
    public boolean contains(String moduleId) {
        return moduleId != null && byId.containsKey(moduleId);
    }
}
