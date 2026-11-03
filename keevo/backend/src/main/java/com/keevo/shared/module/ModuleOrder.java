package com.keevo.shared.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Story 16.12 — the order in which modules create their tables in a tenant schema (registration and provisioning),
 * derived from the installed manifests, never written down twice (Strategy: one ordering rule shared by the tenant
 * provisioner and the module provisioner).
 *
 * <p>Rule: a topological order on {@code requires} (Kahn), where, among the modules whose requirements are all
 * placed, the next one is chosen by
 * <ol>
 *   <li>foundation modules ({@link #isFoundation}) before the others — the paid modules come after every free
 *       table they may reference;</li>
 *   <li>shallower {@code requires} depth first (depth = longest {@code requires} path to {@code core}): every
 *       module that only needs {@code core} creates its tables before the modules that build on several of them;
 *       this places {@code store} and {@code catalog} before {@code inventory} (its tables reference
 *       {@code stores} and {@code products}) and {@code crm} before {@code pos} ({@code sales.client_id → clients}),
 *       which the module manifests declare as {@code enhances} only;</li>
 *   <li>a module listed in the {@code enhances} of another candidate of the same rank before it (cycles in
 *       {@code enhances}, e.g. {@code crm ↔ pos}, fall back to the id);</li>
 *   <li>manifest id (stable).</li>
 * </ol>
 * The foundation order is pinned by a unit test.
 */
public final class ModuleOrder {

    private ModuleOrder() {
    }

    /**
     * Whether the module is a foundation module: free and not a platform module. Its tables exist in every tenant
     * schema (created at registration, kept up to date on every tenant), whatever the tenant's module states.
     */
    public static boolean isFoundation(ModuleManifest module) {
        return module.free() && module.category() != ModuleCategory.PLATFORM;
    }

    /** The modules in table-creation order (see the class doc); unknown {@code requires} are ignored. */
    public static List<ModuleManifest> order(Collection<ModuleManifest> modules) {
        Map<String, ModuleManifest> byId = new HashMap<>();
        modules.forEach(m -> byId.put(m.id(), m));
        Map<String, Integer> depth = new HashMap<>();
        modules.forEach(m -> depth(m, byId, depth, new HashSet<>()));
        Map<String, Integer> pending = new HashMap<>();
        Map<String, List<ModuleManifest>> dependents = new HashMap<>();
        for (ModuleManifest module : modules) {
            int count = 0;
            for (String required : module.requires()) {
                if (byId.containsKey(required) && !required.equals(module.id())) {
                    count++;
                    dependents.computeIfAbsent(required, k -> new ArrayList<>()).add(module);
                }
            }
            pending.put(module.id(), count);
        }
        Comparator<ModuleManifest> rank = Comparator
                .comparing((ModuleManifest m) -> isFoundation(m) ? 0 : 1)
                .thenComparing(m -> depth.get(m.id()));
        List<ModuleManifest> ready = new ArrayList<>();
        modules.stream().filter(m -> pending.get(m.id()) == 0).forEach(ready::add);
        List<ModuleManifest> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            ModuleManifest next = pick(ready, rank);
            ready.remove(next);
            order.add(next);
            for (ModuleManifest dependent : dependents.getOrDefault(next.id(), List.of())) {
                if (pending.merge(dependent.id(), -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != modules.size()) {
            throw new IllegalArgumentException("The module requires graph has a cycle");
        }
        return List.copyOf(order);
    }

    /**
     * The best candidate: lowest rank; among equal ranks, one that no other equal-rank candidate lists in its
     * {@code enhances} is preferred last — i.e. a module enhanced by another candidate goes first; then the id.
     */
    private static ModuleManifest pick(List<ModuleManifest> ready, Comparator<ModuleManifest> rank) {
        ModuleManifest best = ready.stream().min(rank.thenComparing(ModuleManifest::id)).orElseThrow();
        List<ModuleManifest> peers = ready.stream().filter(m -> rank.compare(m, best) == 0)
                .sorted(Comparator.comparing(ModuleManifest::id)).toList();
        // a peer listed in the enhances of another peer, which itself is not listed by that one (no 2-cycle)
        for (ModuleManifest candidate : peers) {
            boolean enhanced = peers.stream().anyMatch(other -> other != candidate
                    && other.enhances().contains(candidate.id()) && !candidate.enhances().contains(other.id()));
            if (enhanced) {
                return candidate;
            }
        }
        return best;
    }

    private static int depth(ModuleManifest module, Map<String, ModuleManifest> byId, Map<String, Integer> depth,
                             Set<String> visiting) {
        Integer known = depth.get(module.id());
        if (known != null) {
            return known;
        }
        if (!visiting.add(module.id())) {
            throw new IllegalArgumentException("The module requires graph has a cycle");
        }
        int d = 0;
        for (String required : module.requires()) {
            ModuleManifest dependency = byId.get(required);
            if (dependency != null && dependency != module) {
                d = Math.max(d, depth(dependency, byId, depth, visiting) + 1);
            }
        }
        visiting.remove(module.id());
        depth.put(module.id(), d);
        return d;
    }
}
