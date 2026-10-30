package com.keevo.shared.module.application;

import com.keevo.shared.module.KeevoModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The {@link KeevoModule} beans of the build, indexed by id (Story 13.9). Validated once, at boot (fail-fast, every
 * problem in one {@link IllegalStateException} naming the classes): a module id is blank / null, is not an
 * installed manifest, or is claimed by two beans. A manifest without a bean is fine (module not converted yet).
 * The core registries ({@link CapabilityRegistryService}, {@link ExtensionRegistryService}) and the lifecycle
 * ({@link ModuleLifecycleService}) read the modules from here.
 */
@Service
@Lazy(false) // always validated at startup, even with spring.main.lazy-initialization=true
public class KeevoModuleDirectory implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(KeevoModuleDirectory.class);

    private final ModuleCatalog catalog;
    private final ListableBeanFactory beans;
    private volatile Map<String, KeevoModule> modules;
    private RuntimeException failure;

    public KeevoModuleDirectory(ModuleCatalog catalog, ListableBeanFactory beans) {
        this.catalog = catalog;
        this.beans = beans;
    }

    /** A directory over already-created modules (tests, tooling); validated now. */
    public static KeevoModuleDirectory of(ModuleCatalog catalog, Collection<? extends KeevoModule> modules) {
        KeevoModuleDirectory directory = new KeevoModuleDirectory(catalog, null);
        directory.build(List.copyOf(modules));
        return directory;
    }

    /** No module at all (a catalogue without converted modules). */
    public static KeevoModuleDirectory empty(ModuleCatalog catalog) {
        return of(catalog, List.of());
    }

    @Override
    public void afterSingletonsInstantiated() {
        all();
    }

    /** The module bean of {@code moduleId}; empty when the module is not converted (or the id unknown). */
    public Optional<KeevoModule> module(String moduleId) {
        return moduleId == null ? Optional.empty() : Optional.ofNullable(index().get(moduleId));
    }

    /** Every module bean, sorted by id. */
    public Collection<KeevoModule> all() {
        return index().values();
    }

    private Map<String, KeevoModule> index() {
        Map<String, KeevoModule> index = modules;
        if (index != null) {
            return index;
        }
        synchronized (this) {
            if (modules == null) {
                if (failure != null) {
                    throw failure;
                }
                if (beans == null) {
                    throw new IllegalStateException("KeevoModuleDirectory has no bean factory and was not initialized");
                }
                build(new ArrayList<>(beans.getBeansOfType(KeevoModule.class, false, true).values()));
            }
            return modules;
        }
    }

    private void build(List<KeevoModule> candidates) {
        try {
            modules = validate(candidates);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        }
    }

    private Map<String, KeevoModule> validate(List<KeevoModule> candidates) {
        List<String> problems = new ArrayList<>();
        Map<String, KeevoModule> byId = new TreeMap<>();
        Map<String, List<String>> claimants = new LinkedHashMap<>();
        List<KeevoModule> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(KeevoModuleDirectory::className));
        for (KeevoModule module : sorted) {
            String name = className(module);
            String id;
            try {
                id = module.id();
            } catch (RuntimeException e) {
                problems.add(name + ": id() failed (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            if (id == null || id.isBlank()) {
                problems.add(name + " has a blank module id");
                continue;
            }
            claimants.computeIfAbsent(id, k -> new ArrayList<>()).add(name);
            if (!catalog.contains(id)) {
                problems.add(name + " is module '" + id + "', which is not installed (no keevo/modules/" + id
                        + "/module.json)");
                continue;
            }
            byId.putIfAbsent(id, module);
        }
        claimants.forEach((id, names) -> {
            if (names.size() > 1) {
                problems.add("module '" + id + "' is implemented by several KeevoModule beans: "
                        + String.join(", ", names));
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid KeevoModule beans: " + String.join("; ", problems));
        }
        byId.forEach((id, module) -> log.info("Module '{}' implemented by {}", id, className(module)));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    }

    static String className(Object module) {
        return ClassUtils.getUserClass(AopProxyUtils.ultimateTargetClass(module)).getName();
    }
}
