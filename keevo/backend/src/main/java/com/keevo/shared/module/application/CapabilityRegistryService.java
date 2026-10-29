package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.Capability;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ProvidesCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
 * hook, {@code spring.main.lazy-initialization=true}) — synchronized, idempotent. Providers are resolved by bean
 * name ({@link ListableBeanFactory#findAnnotationOnBean}: class-level or {@code @Bean}-method annotation, JDK or
 * CGLIB proxies); {@code scopedTarget.*} definitions are skipped and a non-singleton (prototype, scoped) provider
 * fails the boot. Every problem is reported at once in an {@link IllegalStateException}; the index is then
 * immutable. Resolution delegates the module state to {@link ModuleRegistry#moduleState} (request-cached), with no
 * transaction of its own. Provider beans are not autowire candidates
 * ({@code shared.module.infrastructure.CapabilityProviderAutowireExclusion}): {@link #find} is the only way in.
 */
@Service
public class CapabilityRegistryService implements CapabilityRegistry, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistryService.class);

    /** One provided capability: its provider module and implementation bean. */
    record Provision(Class<? extends Capability> type, String module, Object implementation, String providerName) {
    }

    /** A provider bean to index; {@code problem} non-null when it cannot be used at all (e.g. not a singleton). */
    record Candidate(String name, ProvidesCapability annotation, Class<?> type, Object bean, String problem) {
    }

    private final ModuleRegistry registry;
    private final ModuleCatalog catalog;
    private final ListableBeanFactory beans;
    private volatile Map<Class<? extends Capability>, Provision> provisions;

    public CapabilityRegistryService(ModuleRegistry registry, ModuleCatalog catalog, ListableBeanFactory beans) {
        this.registry = registry;
        this.catalog = catalog;
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ensureInitialized();
    }

    private Map<Class<? extends Capability>, Provision> ensureInitialized() {
        Map<Class<? extends Capability>, Provision> index = provisions;
        if (index != null) {
            return index;
        }
        synchronized (this) {
            if (provisions == null) {
                if (beans == null) {
                    throw new IllegalStateException("CapabilityRegistry has no bean factory and was not initialized");
                }
                provisions = build(collect());
            }
            return provisions;
        }
    }

    /** The {@link ProvidesCapability} beans of the factory, by name. */
    private List<Candidate> collect() {
        List<Candidate> candidates = new ArrayList<>();
        for (String name : beans.getBeanNamesForAnnotation(ProvidesCapability.class)) {
            if (ScopedProxyUtils.isScopedTarget(name)) {
                continue; // the target of a scoped proxy: reported through its proxy below
            }
            ProvidesCapability annotation = beans.findAnnotationOnBean(name, ProvidesCapability.class);
            Class<?> type = beans.getType(name);
            boolean scoped = beans.containsBean(ScopedProxyUtils.getTargetBeanName(name));
            if (scoped || !beans.isSingleton(name)) {
                candidates.add(new Candidate(name, annotation, type, null, "capability provider bean '" + name + "' ("
                        + (type != null ? ClassUtils.getUserClass(type).getName() : "?")
                        + ") must be singleton-scoped (prototype / scoped providers are not supported)"));
                continue;
            }
            candidates.add(new Candidate(name, annotation, type, beans.getBean(name), null));
        }
        return candidates;
    }

    /**
     * Indexes and validates already-created provider beans (tests, tooling): each must carry
     * {@link ProvidesCapability} on its class. Once only.
     *
     * @throws IllegalStateException listing every problem found (nothing is indexed then), or when already
     *         initialized
     */
    public synchronized void initialize(Collection<?> providers) {
        if (provisions != null) {
            throw new IllegalStateException("CapabilityRegistry already initialized");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Object bean : providers) {
            Class<?> type = AopProxyUtils.ultimateTargetClass(bean);
            candidates.add(new Candidate(type.getName(),
                    AnnotatedElementUtils.findMergedAnnotation(type, ProvidesCapability.class), bean.getClass(), bean,
                    null));
        }
        provisions = build(candidates);
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

    private Map<Class<? extends Capability>, Provision> build(List<Candidate> candidates) {
        List<String> problems = new ArrayList<>();
        Map<Class<? extends Capability>, Provision> index = new LinkedHashMap<>();
        Map<String, Set<String>> skippedDuplicates = new LinkedHashMap<>(); // module → names already reported
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(Candidate::name));
        for (Candidate candidate : sorted) {
            index(candidate, index, skippedDuplicates, problems);
        }
        checkManifests(index, skippedDuplicates, problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid capability providers: " + String.join("; ", problems));
        }
        index.values().forEach(p -> log.info("Capability {} provided by module '{}' ({})",
                p.type().getSimpleName(), p.module(), p.providerName()));
        return Map.copyOf(index);
    }

    private void index(Candidate candidate, Map<Class<? extends Capability>, Provision> index,
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

    private static String providerName(Candidate candidate) {
        Class<?> target = candidate.bean() != null ? AopProxyUtils.ultimateTargetClass(candidate.bean())
                : candidate.type();
        String className = target != null ? ClassUtils.getUserClass(target).getName() : "?";
        return className.equals(candidate.name()) ? className : className + " (bean '" + candidate.name() + "')";
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
                            + manifest.id() + "\") implements it");
                }
            }
        }
    }
}
