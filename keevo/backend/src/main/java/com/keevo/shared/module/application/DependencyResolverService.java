package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ActivationPlan;
import com.keevo.shared.module.BlockReason;
import com.keevo.shared.module.DeactivationCheck;
import com.keevo.shared.module.DependencyResolver;
import com.keevo.shared.module.ModuleActivationEligibility;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import com.keevo.shared.module.ModuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link DependencyResolver} of the core (Story 13.3): the {@code requires} graph comes from the {@link ModuleCatalog}
 * (validated and acyclic since 13.1, re-checked here), the tenant's effective active set from
 * {@link ModuleRegistry#activeModules(String)} (free modules without a {@code tenant_modules} row are active), the
 * entitlement step from {@link ModuleActivationEligibility}. No database access of its own, no writes.
 */
@Service
public class DependencyResolverService implements DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(DependencyResolverService.class);

    private final ModuleCatalog catalog;
    private final ModuleRegistry registry;
    private final ModuleActivationEligibility eligibility;
    /** module id → its {@code requires} (installed ids only, as validated by the loader). */
    private final Map<String, List<String>> requires;
    /** module id → the modules that require it directly, sorted. */
    private final Map<String, Set<String>> requiredBy;

    public DependencyResolverService(ModuleCatalog catalog, ModuleRegistry registry,
                                     ModuleActivationEligibility eligibility) {
        this.catalog = catalog;
        this.registry = registry;
        this.eligibility = eligibility;
        Map<String, List<String>> graph = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        for (ModuleManifest manifest : catalog.manifests()) {
            Set<String> distinct = new TreeSet<>();
            for (String dependency : manifest.requires()) {
                if (!catalog.contains(dependency)) {
                    throw new IllegalStateException("Module '" + manifest.id() + "' requires '" + dependency
                            + "', which is not in the module catalogue");
                }
                if (!distinct.add(dependency)) {
                    throw new IllegalStateException("Module '" + manifest.id() + "' requires '" + dependency
                            + "' more than once");
                }
            }
            graph.put(manifest.id(), manifest.requires());
            reverse.computeIfAbsent(manifest.id(), k -> new TreeSet<>());
            for (String dependency : manifest.requires()) {
                reverse.computeIfAbsent(dependency, k -> new TreeSet<>()).add(manifest.id());
            }
        }
        List<List<String>> cycles = ModuleManifestLoader.findRequiresCycles(graph);
        if (!cycles.isEmpty()) {
            throw new IllegalStateException("Cycle in the 'requires' graph of the module catalogue: " + cycles);
        }
        this.requires = Map.copyOf(graph);
        this.requiredBy = Map.copyOf(reverse);
    }

    @Override
    public ActivationPlan planActivation(String tenantSchema, String moduleId) {
        ModuleManifest target = checkArguments(tenantSchema, moduleId);
        Set<String> active = registry.activeModules(tenantSchema);

        // closure of the target over 'requires' (target included, core excluded: always active)
        Set<String> closure = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(target.id()));
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (ModuleManifest.CORE_ID.equals(id) || !closure.add(id)) {
                continue;
            }
            todo.addAll(requires.getOrDefault(id, List.of()));
        }
        // active target: its inactive dependencies (if any) form a repair plan; the target itself is not re-planned
        List<String> alreadyActive = closure.stream().filter(active::contains).toList();
        Set<String> needed = new TreeSet<>(closure);
        needed.removeAll(active);

        List<ActivationPlan.Step> steps = new ArrayList<>();
        List<BlockReason> reasons = new ArrayList<>();
        for (String id : topologicalOrder(needed)) {
            ModuleManifest manifest = catalog.find(id).orElseThrow();
            ModuleActivationEligibility.Eligibility outcome = checkEligibility(tenantSchema, manifest);
            steps.add(new ActivationPlan.Step(id, outcome.eligible()));
            if (!outcome.eligible()) {
                reasons.add(new BlockReason(outcome.reason(), id));
            }
        }
        return new ActivationPlan(target.id(), steps, alreadyActive, reasons);
    }

    @Override
    public DeactivationCheck checkDeactivation(String tenantSchema, String moduleId) {
        ModuleManifest target = checkArguments(tenantSchema, moduleId);
        if (target.isCore()) {
            return new DeactivationCheck(target.id(), List.of(), true);
        }
        Set<String> active = registry.activeModules(tenantSchema);

        // every module requiring the target, transitively; only the active ones block
        Set<String> seen = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>(requiredBy.getOrDefault(target.id(), Set.of()));
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (seen.add(id)) {
                todo.addAll(requiredBy.getOrDefault(id, Set.of()));
            }
        }
        return new DeactivationCheck(target.id(), seen.stream().filter(active::contains).toList(), false);
    }

    /**
     * The eligibility of one step (Story 13.5 contract): a {@code null} answer or an exception of the port counts as
     * not eligible, reason {@code ELIGIBILITY_UNAVAILABLE} — logged with the module id only (no tenant data, no
     * exception message, which could carry it).
     */
    private ModuleActivationEligibility.Eligibility checkEligibility(String tenantSchema, ModuleManifest manifest) {
        try {
            ModuleActivationEligibility.Eligibility outcome = eligibility.check(tenantSchema, manifest);
            if (outcome != null) {
                return outcome;
            }
            log.warn("Activation plan: eligibility of module '{}' answered nothing — not eligible", manifest.id());
        } catch (RuntimeException e) {
            log.warn("Activation plan: eligibility of module '{}' failed ({}) — not eligible", manifest.id(),
                    e.getClass().getSimpleName());
        }
        return ModuleActivationEligibility.Eligibility.refused(BlockReason.Code.ELIGIBILITY_UNAVAILABLE);
    }

    /** Validates the schema (tenant, not platform admin) and the module id; returns the module's manifest. */
    private ModuleManifest checkArguments(String tenantSchema, String moduleId) {
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            throw new IllegalArgumentException("The platform-admin schema has no tenant modules");
        }
        TenantSchema.validate(tenantSchema);
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("A module id is required");
        }
        // Story 12.12 (D5): never echo the raw id (caller-controlled) — length only
        return catalog.find(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown module (length=" + moduleId.length() + ")"));
    }

    /**
     * Kahn's topological sort of {@code nodes} over their {@code requires} edges restricted to {@code nodes}
     * (dependencies first), ties broken by module id.
     */
    private List<String> topologicalOrder(Set<String> nodes) {
        Map<String, Integer> pending = new HashMap<>();
        for (String id : nodes) {
            pending.put(id, (int) requires.getOrDefault(id, List.of()).stream().filter(nodes::contains).count());
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        pending.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dependent : requiredBy.getOrDefault(id, Set.of())) {
                if (pending.containsKey(dependent) && pending.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new IllegalStateException("Cycle in the 'requires' graph among " + nodes);
        }
        return order;
    }
}
