package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.Capability;
import com.keevo.shared.module.CapabilityRegistrar;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ProvidesCapability;
import com.keevo.shared.module.application.ModuleBeans.Found;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link CapabilityRegistry} of the core (Story 13.6).
 *
 * <p>The providers are collected and validated once: when every singleton exists
 * ({@link #afterSingletonsInstantiated()}) or at the first {@link #find} if that comes earlier (a consumer's init
 * hook, {@code spring.main.lazy-initialization=true}) — synchronized, idempotent. Providers are discovered by
 * {@link ModuleBeans} (by bean name, singleton-only, shared with the extension registry). Every problem is reported at once in an {@link IllegalStateException}; the index is then
 * immutable. Resolution delegates the module state to {@link ModuleRegistry#moduleState} (request-cached), with no
 * transaction of its own. Provider beans are not autowire candidates
 * ({@code shared.module.infrastructure.ModuleBeanAutowireExclusion}): {@link #find} is the only way in.
 *
 * <p><b>Programmatic providers (Story 13.9).</b> Each {@link KeevoModule} of the {@link KeevoModuleDirectory} is
 * asked once, at initialization, to {@link KeevoModule#registerCapabilities register its capabilities}; what it
 * provides goes into the same index with the same validation (capability interface implemented, duplicates across
 * annotated and programmatic providers, manifest {@code provides} both ways). A {@link CapabilityRegistrar} used
 * after that call is refused.
 */
@Service
@Lazy(false) // always validated at startup, even with spring.main.lazy-initialization=true
public class CapabilityRegistryService implements CapabilityRegistry, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistryService.class);

    /** One provided capability: its provider module and implementation bean. */
    record Provision(Class<? extends Capability> type, String module, Object implementation, String providerName) {
    }

    private final ModuleRegistry registry;
    private final ModuleCatalog catalog;
    private final ListableBeanFactory beans;
    private volatile Map<Class<? extends Capability>, Provision> provisions;
    /** A cached initialization failure (rethrown by every later call). */
    private RuntimeException failure;

    public CapabilityRegistryService(ModuleRegistry registry, ModuleCatalog catalog, ListableBeanFactory beans) {
        this.registry = registry;
        this.catalog = catalog;
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ensureInitialized();
    }

    /**
     * The index, built once. Normally at {@link #afterSingletonsInstantiated()} (the bean is {@code @Lazy(false)});
     * the first call is only a fallback (e.g. a consumer's init hook running earlier). A validation failure is
     * cached and rethrown as is — never rebuilt.
     */
    private Map<Class<? extends Capability>, Provision> ensureInitialized() {
        Map<Class<? extends Capability>, Provision> index = provisions;
        if (index != null) {
            return index;
        }
        synchronized (this) {
            if (provisions == null) {
                if (failure != null) {
                    throw failure;
                }
                if (beans == null) {
                    throw new IllegalStateException("CapabilityRegistry has no bean factory and was not initialized");
                }
                KeevoModuleDirectory directory = beans.getBeanProvider(KeevoModuleDirectory.class).getIfAvailable();
                buildOnce(ModuleBeans.collect(beans, ProvidesCapability.class, "capability provider"),
                        directory != null ? List.copyOf(directory.all()) : List.of());
            }
            return provisions;
        }
    }

    /** Builds the index, caching a failure. Caller holds the lock. */
    private void buildOnce(List<Found<ProvidesCapability>> found, List<KeevoModule> modules) {
        try {
            provisions = build(found, modules);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        }
    }

    /**
     * Indexes and validates already-created providers (tests, tooling): each is either a {@link KeevoModule} (its
     * {@link KeevoModule#registerCapabilities} is called) or a bean carrying {@link ProvidesCapability} on its class.
     * Once only.
     *
     * @throws IllegalStateException listing every problem found (nothing is indexed then), or when already
     *         initialized
     */
    public synchronized void initialize(Collection<?> providers) {
        if (provisions != null || failure != null) {
            throw new IllegalStateException("CapabilityRegistry already initialized");
        }
        List<Found<ProvidesCapability>> candidates = new ArrayList<>();
        List<KeevoModule> modules = new ArrayList<>();
        for (Object bean : providers) {
            if (bean instanceof KeevoModule module) {
                modules.add(module);
            } else {
                candidates.add(ModuleBeans.ofInstance(bean, ProvidesCapability.class));
            }
        }
        buildOnce(candidates, modules);
    }

    @Override
    public <T extends Capability> Optional<T> find(Class<T> type, String tenantSchema) {
        Objects.requireNonNull(type, "capability type");
        Map<Class<? extends Capability>, Provision> index = ensureInitialized();
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return Optional.empty();
        }
        TenantSchema.validate(tenantSchema);
        Provision provision = index.get(type);
        if (provision == null || !registry.moduleState(tenantSchema, provision.module()).readable()) {
            return Optional.empty();
        }
        return Optional.of(type.cast(provision.implementation()));
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────────────

    private Map<Class<? extends Capability>, Provision> build(List<Found<ProvidesCapability>> candidates,
                                                              List<KeevoModule> modules) {
        List<String> problems = new ArrayList<>();
        Map<Class<? extends Capability>, Provision> index = new LinkedHashMap<>();
        Map<String, Set<String>> skippedDuplicates = new LinkedHashMap<>(); // module → names already reported
        List<Found<ProvidesCapability>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(c -> c.name()));
        for (Found<ProvidesCapability> candidate : sorted) {
            index(candidate, index, skippedDuplicates, problems);
        }
        List<KeevoModule> sortedModules = new ArrayList<>(modules);
        sortedModules.sort(Comparator.comparing(KeevoModuleDirectory::className));
        for (KeevoModule module : sortedModules) {
            indexProgrammatic(module, index, skippedDuplicates, problems);
        }
        checkManifests(index, skippedDuplicates, problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid capability providers: " + String.join("; ", problems));
        }
        index.values().forEach(p -> log.info("Capability {} provided by module '{}' ({})",
                p.type().getSimpleName(), p.module(), p.providerName()));
        return Map.copyOf(index);
    }

    private void index(Found<ProvidesCapability> candidate, Map<Class<? extends Capability>, Provision> index,
                       Map<String, Set<String>> skippedDuplicates, List<String> problems) {
        if (candidate.problem() != null) {
            problems.add(candidate.problem());
            if (candidate.annotation() != null && candidate.type() != null) { // its manifest entries: not reported again
                Set<String> names = skippedDuplicates.computeIfAbsent(candidate.annotation().module(),
                        m -> new HashSet<>());
                ClassUtils.getAllInterfacesForClassAsSet(ClassUtils.getUserClass(candidate.type())).stream()
                        .filter(CapabilityRegistryService::isCapabilityInterface)
                        .forEach(type -> names.add(type.getSimpleName()));
                for (Class<?> type : candidate.annotation().value()) {
                    names.add(type.getSimpleName());
                }
            }
            return;
        }
        Object bean = candidate.bean();
        String name = providerName(candidate);
        ProvidesCapability annotation = candidate.annotation();
        if (annotation == null) {
            problems.add(name + " is not annotated @ProvidesCapability");
            return;
        }
        String module = annotation.module();
        boolean installed = catalog.contains(module);
        if (!installed) {
            problems.add(name + " provides for module '" + module + "', which is not installed");
        }
        Class<?> type = candidate.type() != null ? candidate.type() : bean.getClass();
        Set<Class<? extends Capability>> types = capabilityTypes(name, type, bean, annotation, problems);
        if (types.isEmpty() && annotation.value().length == 0) {
            problems.add(name + " (module '" + module + "') implements no capability interface (an interface "
                    + "extending " + Capability.class.getSimpleName() + ")");
        }
        for (Class<? extends Capability> capability : types) {
            Provision previous = index.get(capability);
            if (previous != null) {
                problems.add("capability " + capability.getName() + " provided twice: by " + previous.providerName()
                        + " (module '" + previous.module() + "') and " + name + " (module '" + module + "')");
                skippedDuplicates.computeIfAbsent(module, m -> new HashSet<>()).add(capability.getSimpleName());
                continue;
            }
            if (installed) {
                index.put(capability, new Provision(capability, module, bean, name));
            }
        }
    }

    /** One {@code provide(type, implementation)} call of a module's registrar. */
    private record Provided(Class<?> type, Object implementation) {
    }

    /** Asks the module for its capabilities (once) and indexes them like annotated providers. */
    private void indexProgrammatic(KeevoModule module, Map<Class<? extends Capability>, Provision> index,
                                   Map<String, Set<String>> skippedDuplicates, List<String> problems) {
        String className = KeevoModuleDirectory.className(module);
        String id = module.id();
        String name = className + " (module '" + id + "', registerCapabilities)";
        List<Provided> provided = new ArrayList<>();
        boolean[] open = {true};
        CapabilityRegistrar registrar = new CapabilityRegistrar() {
            @Override
            public <T extends Capability> void provide(Class<T> type, T implementation) {
                if (!open[0]) {
                    throw new IllegalStateException("Capabilities are registered at startup only");
                }
                provided.add(new Provided(type, implementation));
            }
        };
        try {
            module.registerCapabilities(registrar);
        } catch (RuntimeException e) {
            problems.add(name + " failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return;
        } finally {
            open[0] = false;
        }
        boolean installed = catalog.contains(id);
        if (!installed) {
            problems.add(name + " provides for module '" + id + "', which is not installed");
        }
        for (Provided p : provided) {
            if (p.type() == null || p.implementation() == null) {
                problems.add(name + " provides a null " + (p.type() == null ? "capability type" : "implementation"));
                continue;
            }
            if (!isCapabilityInterface(p.type())) {
                problems.add(name + " provides " + p.type().getName() + ", which is not an interface extending "
                        + Capability.class.getSimpleName());
                continue;
            }
            if (!p.type().isInstance(p.implementation())) {
                problems.add(name + " provides " + p.type().getName() + " with an implementation that does not "
                        + "implement it");
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Capability> capability = (Class<? extends Capability>) p.type();
            Provision previous = index.get(capability);
            if (previous != null) {
                problems.add("capability " + capability.getName() + " provided twice: by " + previous.providerName()
                        + " (module '" + previous.module() + "') and " + name + " (module '" + id + "')");
                skippedDuplicates.computeIfAbsent(id, m -> new HashSet<>()).add(capability.getSimpleName());
                continue;
            }
            if (installed) {
                index.put(capability, new Provision(capability, id, p.implementation(), name));
            }
        }
    }

    private static String providerName(Found<ProvidesCapability> candidate) {
        return ModuleBeans.describe(candidate);
    }

    @SuppressWarnings("unchecked")
    private static Set<Class<? extends Capability>> capabilityTypes(String name, Class<?> beanType, Object bean,
                                                                    ProvidesCapability annotation,
                                                                    List<String> problems) {
        Set<Class<? extends Capability>> types = new LinkedHashSet<>();
        if (annotation.value().length > 0) {
            for (Class<? extends Capability> type : annotation.value()) {
                if (!isCapabilityInterface(type)) {
                    problems.add(name + " declares " + type.getName()
                            + ", which is not an interface extending " + Capability.class.getSimpleName());
                } else if (!type.isInstance(bean)) {
                    problems.add(name + " declares " + type.getName() + " but does not implement it");
                } else {
                    types.add(type);
                }
            }
            return types;
        }
        // implicit: only the most specific capability interfaces (a parent of another one is not registered)
        List<Class<?>> all = ClassUtils.getAllInterfacesForClassAsSet(ClassUtils.getUserClass(beanType)).stream()
                .filter(CapabilityRegistryService::isCapabilityInterface)
                .toList();
        all.stream()
                .filter(candidate -> all.stream().noneMatch(other -> other != candidate && candidate.isAssignableFrom(other)))
                .filter(type -> type.isInstance(bean))
                .sorted(Comparator.comparing(Class::getName))
                .forEach(type -> types.add((Class<? extends Capability>) type));
        return types;
    }

    private static boolean isCapabilityInterface(Class<?> type) {
        return type != null && type.isInterface() && type != Capability.class && Capability.class.isAssignableFrom(type);
    }

    /** Provider ↔ manifest {@code provides}, both ways, by capability simple name. */
    private void checkManifests(Map<Class<? extends Capability>, Provision> index,
                                Map<String, Set<String>> skippedDuplicates, List<String> problems) {
        Map<String, Map<String, Provision>> byModule = new LinkedHashMap<>();
        for (Provision p : index.values()) {
            Provision clash = byModule.computeIfAbsent(p.module(), m -> new LinkedHashMap<>())
                    .putIfAbsent(p.type().getSimpleName(), p);
            if (clash != null) {
                problems.add("module '" + p.module() + "' provides two capabilities named '"
                        + p.type().getSimpleName() + "': " + clash.type().getName() + " and " + p.type().getName());
            }
            ModuleManifest manifest = catalog.find(p.module()).orElseThrow();
            if (!manifest.provides().contains(p.type().getSimpleName())) {
                problems.add(p.providerName() + " provides " + p.type().getName()
                        + " but the manifest of module '" + p.module() + "' does not list '"
                        + p.type().getSimpleName() + "' in 'provides'");
            }
        }
        for (ModuleManifest manifest : catalog.manifests()) {
            Map<String, Provision> provided = byModule.getOrDefault(manifest.id(), Map.of());
            Set<String> skipped = skippedDuplicates.getOrDefault(manifest.id(), Set.of());
            for (String entry : manifest.provides()) {
                if (!provided.containsKey(entry) && !skipped.contains(entry)) {
                    problems.add("the manifest of module '" + manifest.id() + "' lists '" + entry
                            + "' in 'provides' but no bean annotated @ProvidesCapability(module = \""
                            + manifest.id() + "\") implements it, and its KeevoModule does not provide it in "
                            + "registerCapabilities");
                }
            }
        }
    }
}
