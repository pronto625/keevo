package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.application.port.ActiveModulesCache;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link ModuleRegistry} of the core (Story 13.2): installed modules from the {@link ModuleCatalog}, per-tenant
 * rows from {@code public.tenant_modules} ({@link TenantModuleStatePort}), cached through
 * {@link ActiveModulesCache} (one read per tenant per request). Since Story 13.5 the effective state is computed
 * by {@link ModuleStateRules} at every call, with the injected {@link Clock} — a cached row past its
 * {@code expires_at} turns read-only within the same request.
 */
@Service
public class ModuleRegistryService implements ModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistryService.class);

    private final ModuleCatalog catalog;
    private final TenantModuleStatePort states;
    private final ActiveModulesCache cache;
    private final Clock clock;

    public ModuleRegistryService(ModuleCatalog catalog, TenantModuleStatePort states, ActiveModulesCache cache,
                                 Clock clock) {
        this.catalog = catalog;
        this.states = states;
        this.cache = cache;
        this.clock = clock;
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
        return sortedImmutable(moduleStates(tenantSchema).entrySet().stream()
                .filter(e -> e.getValue().readable())
                .map(Map.Entry::getKey).toList());
    }

    @Override
    public boolean isKnownTenant(String tenantSchema) {
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return false;
        }
        return lookup(tenantSchema).knownTenant();
    }

    @Override
    public boolean isActive(String tenantSchema, String moduleId) {
        return moduleState(tenantSchema, moduleId).readable();
    }

    @Override
    public ModuleState moduleState(String tenantSchema, String moduleId) {
        Map<String, ModuleState> all = moduleStates(tenantSchema); // validates the schema first, even for a bad id
        ModuleState state = moduleId != null ? all.get(moduleId) : null;
        return state != null ? state : ModuleState.DISABLED;
    }

    @Override
    public Map<String, ModuleState> moduleStates(String tenantSchema) {
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return platformAdminStates(catalog.manifests());
        }
        ActiveModulesCache.Entry entry = lookup(tenantSchema);
        return effectiveStates(catalog.manifests(),
                entry.knownTenant() ? Optional.of(entry.rows()) : Optional.empty(), entry.plan(), clock);
    }

    /** The tenant's entry: from the request cache, else one query (cached for the rest of the request). */
    private ActiveModulesCache.Entry lookup(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        Optional<ActiveModulesCache.Entry> cached = cache.get(schema);
        if (cached.isPresent()) {
            return cached.get();
        }
        Optional<TenantModuleStatePort.TenantRows> found = states.findTenantRows(schema);
        if (found.isEmpty()) {
            log.debug("Module registry: no tenant registered for the given schema — only core is active");
        }
        ActiveModulesCache.Entry entry = found
                .map(t -> new ActiveModulesCache.Entry(true, t.rows(), t.plan()))
                .orElseGet(() -> new ActiveModulesCache.Entry(false, Map.of(), TenantModuleStatePort.Plan.NONE));
        cache.put(schema, entry);
        return entry;
    }

    @Override
    public void evict(String tenantSchema) {
        if (TenantSchema.isValid(tenantSchema)) {
            cache.evict(tenantSchema);
        }
    }

    /** Platform-admin session ({@code public}): {@code core} + the installed {@code platform} modules, no query. */
    static Map<String, ModuleState> platformAdminStates(Collection<ModuleManifest> installed) {
        Map<String, ModuleState> states = new LinkedHashMap<>();
        installed.stream().map(ModuleManifest::id).sorted().forEach(id -> states.put(id, ModuleState.DISABLED));
        installed.stream()
                .filter(m -> m.isCore() || m.category() == ModuleCategory.PLATFORM)
                .forEach(m -> states.put(m.id(), ModuleState.ENABLED));
        return Collections.unmodifiableMap(states);
    }

    /** Sorted, immutable, and {@code contains(null)} answers {@code false} (unlike an unmodifiable TreeSet). */
    private static Set<String> sortedImmutable(Collection<String> ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(ids)));
    }

    /**
     * The effective state of every installed module ({@link ModuleStateRules}), evaluated at {@code clock.instant()}.
     *
     * @param installed the installed manifests
     * @param rows      the tenant's rows (module id → row), empty when the tenant is unknown
     * @param plan      the tenant's plan, for the paid modules without a row (Story 13.10a transition rule)
     * @return module id → state, immutable, sorted by id, {@code get(null)} answers {@code null}
     */
    static Map<String, ModuleState> effectiveStates(Collection<ModuleManifest> installed,
                                                    Optional<Map<String, TenantModuleStatePort.Row>> rows,
                                                    TenantModuleStatePort.Plan plan, Clock clock) {
        var now = clock.instant();
        Map<String, ModuleState> states = new LinkedHashMap<>();
        installed.stream()
                .sorted(java.util.Comparator.comparing(ModuleManifest::id))
                .forEach(m -> states.put(m.id(), ModuleStateRules.stateOf(m, rows, plan, now)));
        return Collections.unmodifiableMap(states);
    }
}
