package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ContributesExtension;
import com.keevo.shared.module.Extension;
import com.keevo.shared.module.ExtensionRegistrar;
import com.keevo.shared.module.ExtensionRegistry;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ExtensionView;
import com.keevo.shared.module.ModuleManifestLoader;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.application.ModuleBeans.Found;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link ExtensionRegistry} of the core (Story 13.7).
 *
 * <p>The declared points come from the catalogue (the manifest loader guarantees their format, owner prefix and
 * uniqueness, and that every {@code fills} targets a declared point). The contributions are collected and validated
 * once — when every singleton exists ({@link #afterSingletonsInstantiated()}) or at the first
 * {@link #contributions} call if earlier — through {@link ModuleBeans} (by bean name, singleton-only, shared with
 * the capability registry); every problem is reported at once in an {@link IllegalStateException}, then the index
 * is immutable and pre-sorted. Resolution reads module states through {@link ModuleRegistry#moduleState}
 * (request-cached), with no transaction of its own; the listed elements are immutable {@link ExtensionView}
 * snapshots built at initialization, never the beans. Contribution beans are not autowire candidates
 * ({@code shared.module.infrastructure.ModuleBeanAutowireExclusion}).
 *
 * <p><b>Programmatic contributions (Story 13.9).</b> Each {@link KeevoModule} of the {@link KeevoModuleDirectory} is
 * asked once, at initialization, to {@link KeevoModule#registerExtensions register its contributions}; they are
 * validated exactly like the annotated ones (same checks, same index). An {@link ExtensionRegistrar} used after
 * that call is refused.
 */
@Service
@Lazy(false) // always validated at startup, even with spring.main.lazy-initialization=true
public class ExtensionRegistryService implements ExtensionRegistry, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistryService.class);

    /** Contributions sorted by order, then module id, then extension id. */
    private static final Comparator<Contribution> ORDER = Comparator.comparingInt(Contribution::order)
            .thenComparing(Contribution::module).thenComparing(Contribution::id);

    /** One validated contribution: its immutable snapshot (values read once at startup) and its bean's name. */
    record Contribution(String point, ExtensionView view, String beanName) {
        String module() {
            return view.module();
        }

        String id() {
            return view.id();
        }

        int order() {
            return view.order();
        }
    }

    private final ModuleRegistry registry;
    private final ModuleCatalog catalog;
    private final ListableBeanFactory beans;
    /** point id → declaring module. */
    private final Map<String, String> declarers;
    private final Set<String> declaredPoints;
    /** Points declared by more than one manifest (the loader prevents it; reported at initialization). */
    private final List<String> declarationClashes;
    private volatile Map<String, List<Contribution>> contributions;
    /** A cached initialization failure (rethrown by every later call). */
    private RuntimeException failure;

    public ExtensionRegistryService(ModuleRegistry registry, ModuleCatalog catalog, ListableBeanFactory beans) {
        this.registry = registry;
        this.catalog = catalog;
        this.beans = beans;
        Map<String, String> byPoint = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (ModuleManifest manifest : catalog.manifests()) {
            for (String point : manifest.extensionPoints().declares()) {
                String previous = byPoint.putIfAbsent(point, manifest.id());
                if (previous != null) {
                    clashes.add("extension point '" + point + "' declared by two modules: '" + previous + "' and '"
                            + manifest.id() + "'");
                }
            }
        }
        this.declarationClashes = List.copyOf(clashes);
        this.declarers = Map.copyOf(byPoint);
        this.declaredPoints = Collections.unmodifiableSortedSet(new TreeSet<>(byPoint.keySet()));
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
    private Map<String, List<Contribution>> ensureInitialized() {
        Map<String, List<Contribution>> index = contributions;
        if (index != null) {
            return index;
        }
        synchronized (this) {
            if (contributions == null) {
                if (failure != null) {
                    throw failure;
                }
                if (beans == null) {
                    throw new IllegalStateException("ExtensionRegistry has no bean factory and was not initialized");
                }
                KeevoModuleDirectory directory = beans.getBeanProvider(KeevoModuleDirectory.class).getIfAvailable();
                buildOnce(ModuleBeans.collect(beans, ContributesExtension.class, "extension contribution"),
                        directory != null ? List.copyOf(directory.all()) : List.of());
            }
            return contributions;
        }
    }

    /** Builds the index, caching a failure. Caller holds the lock. */
    private void buildOnce(List<Found<ContributesExtension>> found, List<KeevoModule> modules) {
        try {
            contributions = build(found, modules);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        }
    }

    /**
     * Indexes and validates already-created contributions (tests, tooling): each is either a {@link KeevoModule} (its
     * {@link KeevoModule#registerExtensions} is called) or a bean carrying {@link ContributesExtension} on its class.
     * Once only.
     *
     * @throws IllegalStateException listing every problem found (nothing is indexed then), or when already
     *         initialized
     */
    public synchronized void initialize(Collection<?> contributionBeans) {
        if (contributions != null || failure != null) {
            throw new IllegalStateException("ExtensionRegistry already initialized");
        }
        List<Found<ContributesExtension>> found = new ArrayList<>();
        List<KeevoModule> modules = new ArrayList<>();
        for (Object bean : contributionBeans) {
            if (bean instanceof KeevoModule module) {
                modules.add(module);
            } else {
                found.add(ModuleBeans.ofInstance(bean, ContributesExtension.class));
            }
        }
        buildOnce(found, modules);
    }

    @Override
    public List<Extension> contributions(String pointId, String tenantSchema) {
        Map<String, List<Contribution>> index = ensureInitialized();
        String declarer = pointId == null ? null : declarers.get(pointId);
        if (declarer == null) {
            throw new IllegalArgumentException("Unknown extension point");
        }
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return List.of();
        }
        TenantSchema.validate(tenantSchema);
        List<Contribution> candidates = index.getOrDefault(pointId, List.of());
        if (candidates.isEmpty() || !registry.isKnownTenant(tenantSchema)
                || !registry.moduleState(tenantSchema, declarer).readable()) {
            return List.of();
        }
        List<Extension> usable = new ArrayList<>(candidates.size());
        for (Contribution contribution : candidates) {
            if (registry.moduleState(tenantSchema, contribution.module()).writable()) {
                usable.add(contribution.view());
            }
        }
        return List.copyOf(usable);
    }

    @Override
    public Set<String> declaredPoints() {
        return declaredPoints;
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────────────

    private Map<String, List<Contribution>> build(List<Found<ContributesExtension>> found,
                                                  List<KeevoModule> modules) {
        List<String> problems = new ArrayList<>(declarationClashes);
        Map<String, List<Contribution>> byPoint = new LinkedHashMap<>();
        Map<String, Set<String>> reported = new HashMap<>(); // module → points already accounted for
        List<Found<ContributesExtension>> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(f -> f.name()));
        for (Found<ContributesExtension> candidate : sorted) {
            index(candidate, byPoint, reported, problems);
        }
        List<KeevoModule> sortedModules = new ArrayList<>(modules);
        sortedModules.sort(Comparator.comparing(KeevoModuleDirectory::className));
        for (KeevoModule module : sortedModules) {
            indexProgrammatic(module, byPoint, reported, problems);
        }
        for (ModuleManifest manifest : catalog.manifests()) {
            Set<String> filled = reported.getOrDefault(manifest.id(), Set.of());
            for (String point : manifest.extensionPoints().fills()) {
                if (!filled.contains(point)) {
                    problems.add("the manifest of module '" + manifest.id() + "' lists '" + point
                            + "' in 'extensionPoints.fills' but no @ContributesExtension bean of module '"
                            + manifest.id() + "' contributes to it (nor its KeevoModule in registerExtensions)");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid extension contributions: " + String.join("; ", problems));
        }
        Map<String, List<Contribution>> index = new HashMap<>();
        byPoint.forEach((point, list) -> {
            list.sort(ORDER);
            list.forEach(c -> log.info("Extension '{}' contributed to '{}' by module '{}' ({})",
                    c.id(), point, c.module(), c.beanName()));
            index.put(point, List.copyOf(list));
        });
        declaredPoints.forEach(point -> {
            if (!index.containsKey(point)) {
                log.info("Extension point '{}' declared by module '{}' (no contribution in this build)", point,
                        declarers.get(point));
            }
        });
        return Map.copyOf(index);
    }

    /** Asks the module for its contributions (once) and validates each like an annotated bean. */
    private void indexProgrammatic(KeevoModule module, Map<String, List<Contribution>> byPoint,
                                   Map<String, Set<String>> reported, List<String> problems) {
        String id = module.id();
        String name = KeevoModuleDirectory.className(module) + " (registerExtensions)";
        List<Object[]> contributed = new ArrayList<>();
        boolean[] open = {true};
        ExtensionRegistrar registrar = (pointId, contribution) -> {
            if (!open[0]) {
                throw new IllegalStateException("Extensions are registered at startup only");
            }
            contributed.add(new Object[]{pointId, contribution});
        };
        try {
            module.registerExtensions(registrar);
        } catch (RuntimeException e) {
            problems.add(name + " of module '" + id + "' failed (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")");
            return;
        } finally {
            open[0] = false;
        }
        for (Object[] c : contributed) {
            String point = (String) c[0];
            if (point == null || point.isBlank()) {
                problems.add(name + " of module '" + id + "' contributes to a blank extension point");
                continue;
            }
            indexOne(name, id, point, c[1], byPoint, reported, problems);
        }
    }

    private void index(Found<ContributesExtension> candidate, Map<String, List<Contribution>> byPoint,
                       Map<String, Set<String>> reported, List<String> problems) {
        ContributesExtension annotation = candidate.annotation();
        String name = ModuleBeans.describe(candidate);
        if (candidate.problem() != null) {
            problems.add(candidate.problem());
            if (annotation != null) { // its manifest entry: not reported a second time
                reported.computeIfAbsent(annotation.module(), m -> new HashSet<>()).add(annotation.point());
            }
            return;
        }
        if (annotation == null) {
            problems.add(name + " is not annotated @ContributesExtension");
            return;
        }
        indexOne(name, annotation.module(), annotation.point(), candidate.bean(), byPoint, reported, problems);
    }

    /** Validates and indexes one contribution of {@code module} to {@code point} (annotated or programmatic). */
    private void indexOne(String name, String module, String point, Object bean,
                          Map<String, List<Contribution>> byPoint, Map<String, Set<String>> reported,
                          List<String> problems) {
        String where = name + " (module '" + module + "', point '" + point + "')";
        reported.computeIfAbsent(module, m -> new HashSet<>()).add(point);
        if (!(bean instanceof Extension extension)) {
            problems.add(where + " does not implement " + Extension.class.getName());
            return;
        }
        ModuleManifest manifest = catalog.find(module).orElse(null);
        if (manifest == null) {
            problems.add(where + " contributes for module '" + module + "', which is not installed");
            return;
        }
        if (!manifest.extensionPoints().fills().contains(point)) {
            problems.add(where + " contributes to '" + point + "' but the manifest of module '" + module
                    + "' does not list it in 'extensionPoints.fills'");
            return;
        }
        String id = extension.id();
        String label = extension.label();
        String action = extension.action();
        String permission = extension.permission();
        boolean valid = validKey(where, "id", id, module, problems);
        valid &= validKey(where, "action", action, module, problems);
        if (label == null || label.isBlank()) {
            problems.add(where + " has a blank label");
            valid = false;
        }
        if (permission != null && !manifest.permissions().contains(permission)) {
            problems.add(where + " requires permission '" + permission + "', which the manifest of module '" + module
                    + "' does not declare in 'permissions'");
            valid = false;
        }
        if (!valid) {
            return;
        }
        List<Contribution> list = byPoint.computeIfAbsent(point, p -> new ArrayList<>());
        for (Contribution other : list) {
            if (other.id().equals(id)) {
                problems.add("extension id '" + id + "' contributed twice to '" + point + "': by " + other.beanName()
                        + " (module '" + other.module() + "') and " + name + " (module '" + module + "')");
                return;
            }
        }
        list.add(new Contribution(point, new ExtensionView(module, id, label, extension.order(), action, permission), name));
    }

    /** An id / action key: {@link ModuleManifestLoader#POINT_PATTERN}, prefixed by the contributing module id. */
    private static boolean validKey(String where, String field, String value, String module, List<String> problems) {
        if (value == null || !ModuleManifestLoader.POINT_PATTERN.matcher(value).matches()
                || !value.startsWith(module + ".")) {
            problems.add(where + " has an invalid " + field + " '" + value + "': it must match "
                    + ModuleManifestLoader.POINT_PATTERN.pattern() + " and start with '" + module + ".'");
            return false;
        }
        return true;
    }
}
