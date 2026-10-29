package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.ActiveModulesCache;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link ModuleRegistry} of the core (Story 13.2): installed modules from the {@link ModuleCatalog}, per-tenant
 * state from {@code public.tenant_modules} ({@link TenantModuleStatePort}), effective state computed by
 * {@link #effectiveActiveModules} and cached through {@link ActiveModulesCache} (one read per tenant per request).
 */
@Service
public class ModuleRegistryService implements ModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistryService.class);

    private final ModuleCatalog catalog;
    private final TenantModuleStatePort states;
    private final ActiveModulesCache cache;

    public ModuleRegistryService(ModuleCatalog catalog, TenantModuleStatePort states, ActiveModulesCache cache) {
        this.catalog = catalog;
        this.states = states;
        this.cache = cache;
    }

    @Override
    public Collection<ModuleManifest> installed() {
        return catalog.manifests();
    }

    @Override
    public Optional<ModuleManifest> definition(String moduleId) {
        return catalog.find(moduleId);
    }

    @Override
    public Set<String> activeModules(String tenantSchema) {
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return platformAdminModules(catalog.manifests());
        }
        return lookup(tenantSchema).activeModules();
    }

    @Override
    public boolean isKnownTenant(String tenantSchema) {
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return false;
        }
        return lookup(tenantSchema).knownTenant();
    }

    /** The tenant's entry: from the request cache, else one query (cached for the rest of the request). */
    private ActiveModulesCache.Entry lookup(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        Optional<ActiveModulesCache.Entry> cached = cache.get(schema);
        if (cached.isPresent()) {
            return cached.get();
        }
        Optional<Map<String, TenantModuleStatus>> rows = states.findStatuses(schema);
        if (rows.isEmpty()) {
            log.debug("Module registry: no tenant registered for the given schema — only core is active");
        }
        ActiveModulesCache.Entry entry = new ActiveModulesCache.Entry(rows.isPresent(),
                effectiveActiveModules(catalog.manifests(), rows));
        cache.put(schema, entry);
        return entry;
    }

    @Override
    public boolean isActive(String tenantSchema, String moduleId) {
        Set<String> active = activeModules(tenantSchema);
        return moduleId != null && active.contains(moduleId);
    }

    @Override
    public void evict(String tenantSchema) {
        if (TenantSchema.isValid(tenantSchema)) {
            cache.evict(tenantSchema);
        }
    }

    /** Platform-admin session ({@code public}): {@code core} + the installed {@code platform} modules, no query. */
    static Set<String> platformAdminModules(Collection<ModuleManifest> installed) {
        return sortedImmutable(installed.stream()
                .filter(m -> m.isCore() || m.category() == ModuleCategory.PLATFORM)
                .map(ModuleManifest::id).toList());
    }

    /** Sorted, immutable, and {@code contains(null)} answers {@code false} (unlike an unmodifiable TreeSet). */
    private static Set<String> sortedImmutable(Collection<String> ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(ids)));
    }

    /**
     * The effective state rule (spec 13.2): only installed modules can be active; {@code core} always is; a
     * {@code tenant_modules} row decides when present (TRIAL/ACTIVE); otherwise the manifest's {@code free} flag
     * decides. An unknown tenant ({@code rows} empty) only gets {@code core}.
     *
     * @param installed the installed manifests
     * @param rows      the tenant's rows (module id → status), empty when the tenant is unknown
     * @return the active module ids, immutable, sorted
     */
    static Set<String> effectiveActiveModules(Collection<ModuleManifest> installed,
                                              Optional<Map<String, TenantModuleStatus>> rows) {
        Set<String> active = new TreeSet<>();
        for (ModuleManifest manifest : installed) {
            String id = manifest.id();
            if (manifest.isCore()) {
                active.add(id);
            } else if (rows.isPresent()) {
                TenantModuleStatus status = rows.get().get(id);
                if (status != null ? status.isActive() : manifest.free()) {
                    active.add(id);
                }
            }
        }
        return sortedImmutable(active);
    }
}
