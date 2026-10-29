package com.keevo.shared.module.application;

import com.keevo.shared.module.ActivationPlan;
import com.keevo.shared.module.BlockReason;
import com.keevo.shared.module.DeactivationCheck;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static com.keevo.shared.module.TestModuleCatalogs.dependencyCatalog;
import static com.keevo.shared.module.TestModuleCatalogs.loaded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 13.3 — every row of the I/O matrix of {@link DependencyResolverService}, on a fake registry. */
class DependencyResolverServiceTest {

    private static final String TENANT = "kv_dep001";

    private final ModuleCatalog catalog = dependencyCatalog();
    private final FakeRegistry registry = new FakeRegistry(catalog);
    private final List<String> eligibilityCalls = new ArrayList<>();
    private final DependencyResolverService resolver = new DependencyResolverService(catalog, registry,
            (schema, module) -> {
                eligibilityCalls.add(schema + ":" + module.id());
                return new FreeModulesOnlyEligibility().check(schema, module);
            });

    // ── activation ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cascade: x requires catalog + inventory (inventory requires catalog) → [catalog, inventory, x]")
    void cascade() {
        ActivationPlan plan = resolver.planActivation(TENANT, "x");
        assertThat(plan.moduleId()).isEqualTo("x");
        assertThat(plan.moduleIds()).containsExactly("catalog", "inventory", "x");
        assertThat(plan.steps()).allMatch(ActivationPlan.Step::eligible);
        assertThat(plan.alreadyActive()).isEmpty();
        assertThat(plan.blocked()).isFalse();
        assertThat(plan.reasons()).isEmpty();
        assertThat(eligibilityCalls).containsExactly(TENANT + ":catalog", TENANT + ":inventory", TENANT + ":x");
    }

    @Test
    @DisplayName("partly active: catalog active → [inventory, x], alreadyActive [catalog]")
    void partlyActive() {
        registry.activate("catalog");
        ActivationPlan plan = resolver.planActivation(TENANT, "x");
        assertThat(plan.moduleIds()).containsExactly("inventory", "x");
        assertThat(plan.alreadyActive()).containsExactly("catalog");
        assertThat(plan.blocked()).isFalse();
    }

    @Test
    @DisplayName("multi-level: alpha → beta → gamma, none active → [gamma, beta, alpha]")
    void multiLevel() {
        assertThat(resolver.planActivation(TENANT, "alpha").moduleIds()).containsExactly("gamma", "beta", "alpha");
        assertThat(resolver.planActivation(TENANT, "beta").moduleIds()).containsExactly("gamma", "beta");
    }

    @Test
    @DisplayName("multi-level, middle active: beta active → [alpha] only (its own requires already met)")
    void multiLevelMiddleActive() {
        registry.activate("beta", "gamma");
        ActivationPlan plan = resolver.planActivation(TENANT, "alpha");
        assertThat(plan.moduleIds()).containsExactly("alpha");
        assertThat(plan.alreadyActive()).containsExactly("beta", "gamma");
    }

    @Test
    @DisplayName("target active, closure active → empty plan, alreadyActive = closure ∪ target, no eligibility check")
    void targetActive() {
        registry.activate("x", "catalog", "inventory");
        ActivationPlan plan = resolver.planActivation(TENANT, "x");
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.alreadyActive()).containsExactly("catalog", "inventory", "x");
        assertThat(plan.blocked()).isFalse();
        assertThat(eligibilityCalls).isEmpty();
    }

    @Test
    @DisplayName("matrix row: target without requires active → empty plan, alreadyActive [target]")
    void targetWithoutRequiresActive() {
        registry.activate("solo");
        ActivationPlan plan = resolver.planActivation(TENANT, "solo");
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.alreadyActive()).containsExactly("solo");
    }

    @Test
    @DisplayName("target active with inactive hard dependencies → repair plan of the missing ones, target not re-planned")
    void targetActiveWithInactiveDependencies() {
        registry.activate("x");
        ActivationPlan plan = resolver.planActivation(TENANT, "x");
        assertThat(plan.moduleIds()).containsExactly("catalog", "inventory");
        assertThat(plan.alreadyActive()).containsExactly("x");
        assertThat(plan.blocked()).isFalse();

        registry.activate("catalog");
        ActivationPlan partial = resolver.planActivation(TENANT, "x");
        assertThat(partial.moduleIds()).containsExactly("inventory");
        assertThat(partial.alreadyActive()).containsExactly("catalog", "x");
    }

    @Test
    @DisplayName("core is never in a plan nor in alreadyActive: planning core → empty plan, empty alreadyActive")
    void coreNeverPlanned() {
        ActivationPlan plan = resolver.planActivation(TENANT, "core");
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.alreadyActive()).isEmpty();
        assertThat(plan.blocked()).isFalse();
        assertThat(resolver.planActivation(TENANT, "solo").moduleIds()).containsExactly("solo");
        assertThat(resolver.planActivation(TENANT, "solo").alreadyActive()).isEmpty();
    }

    @Test
    @DisplayName("paid dependency → blocked NOT_ENTITLED paid, the step marked ineligible")
    void paidDependency() {
        ActivationPlan plan = resolver.planActivation(TENANT, "buyer");
        assertThat(plan.moduleIds()).containsExactly("paid", "buyer");
        assertThat(plan.steps()).containsExactly(new ActivationPlan.Step("paid", false),
                new ActivationPlan.Step("buyer", true));
        assertThat(plan.blocked()).isTrue();
        assertThat(plan.reasons()).containsExactly(new BlockReason(BlockReason.Code.NOT_ENTITLED, "paid"));
    }

    @Test
    @DisplayName("paid dependency already active → not blocked (only the steps to run are checked)")
    void paidDependencyAlreadyActive() {
        registry.activate("paid");
        ActivationPlan plan = resolver.planActivation(TENANT, "buyer");
        assertThat(plan.moduleIds()).containsExactly("buyer");
        assertThat(plan.alreadyActive()).containsExactly("paid");
        assertThat(plan.blocked()).isFalse();
    }

    @Test
    @DisplayName("enhances only: helper enhances beta → planning helper does not pull beta")
    void enhancesDoesNotConstrainActivation() {
        assertThat(resolver.planActivation(TENANT, "helper").moduleIds()).containsExactly("helper");
    }

    @Test
    @DisplayName("tie-break by id and diamond: top requires [zeta, eta], both require base → [base, eta, zeta, top]")
    void tieBreakAndDiamond() {
        assertThat(resolver.planActivation(TENANT, "top").moduleIds()).containsExactly("base", "eta", "zeta", "top");
        registry.activate("eta");
        assertThat(resolver.planActivation(TENANT, "top").moduleIds()).containsExactly("base", "zeta", "top");
    }

    // ── deactivation ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivate a module required by an active one → blocked, dependents [alpha]")
    void deactivateRequired() {
        registry.activate("alpha", "beta", "gamma");
        DeactivationCheck check = resolver.checkDeactivation(TENANT, "beta");
        assertThat(check.blocked()).isTrue();
        assertThat(check.allowed()).isFalse();
        assertThat(check.dependents()).containsExactly("alpha");
        assertThat(check.reasons()).containsExactly(
                new BlockReason(BlockReason.Code.REQUIRED_BY_ACTIVE_MODULE, "alpha"));
    }

    @Test
    @DisplayName("deactivate transitive: alpha → beta → gamma all active, deactivate gamma → dependents [alpha, beta]")
    void deactivateTransitive() {
        registry.activate("alpha", "beta", "gamma");
        DeactivationCheck check = resolver.checkDeactivation(TENANT, "gamma");
        assertThat(check.blocked()).isTrue();
        assertThat(check.dependents()).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("dependents come from the effective active set: inactive dependents do not block")
    void inactiveDependentsDoNotBlock() {
        registry.activate("gamma");
        DeactivationCheck check = resolver.checkDeactivation(TENANT, "gamma");
        assertThat(check.allowed()).isTrue();
        assertThat(check.dependents()).isEmpty();
        assertThat(check.reasons()).isEmpty();
    }

    @Test
    @DisplayName("deactivate free: no active dependents → allowed")
    void deactivateFree() {
        registry.activate("solo", "catalog");
        assertThat(resolver.checkDeactivation(TENANT, "solo").allowed()).isTrue();
        assertThat(resolver.checkDeactivation(TENANT, "catalog").allowed()).isTrue();
    }

    @Test
    @DisplayName("deactivate core → blocked CORE_MODULE")
    void deactivateCore() {
        DeactivationCheck check = resolver.checkDeactivation(TENANT, "core");
        assertThat(check.blocked()).isTrue();
        assertThat(check.dependents()).isEmpty();
        assertThat(check.reasons()).containsExactly(new BlockReason(BlockReason.Code.CORE_MODULE, "core"));
    }

    @Test
    @DisplayName("enhances only: helper active enhances beta → deactivating beta is allowed, and helper too")
    void enhancesDoesNotConstrainDeactivation() {
        registry.activate("helper", "beta", "gamma");
        assertThat(resolver.checkDeactivation(TENANT, "beta").allowed()).isTrue();
        assertThat(resolver.checkDeactivation(TENANT, "helper").allowed()).isTrue();
    }

    @Test
    @DisplayName("DeactivationCheck derives its reasons from its dependents (sorted, deduplicated)")
    void deactivationReasonsDerived() {
        DeactivationCheck check = new DeactivationCheck("beta", List.of("zeta", "alpha", "alpha"), false);
        assertThat(check.dependents()).containsExactly("alpha", "zeta");
        assertThat(check.reasons()).containsExactly(
                new BlockReason(BlockReason.Code.REQUIRED_BY_ACTIVE_MODULE, "alpha"),
                new BlockReason(BlockReason.Code.REQUIRED_BY_ACTIVE_MODULE, "zeta"));
        assertThat(new DeactivationCheck("beta", List.of(), false).reasons()).isEmpty();
        assertThat(new DeactivationCheck("beta", List.of(), false).allowed()).isTrue();
        assertThatThrownBy(() -> new DeactivationCheck("core", List.of("alpha"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── errors ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unknown module → IllegalArgumentException, for both operations")
    void unknownModule() {
        assertThatThrownBy(() -> resolver.planActivation(TENANT, "ghost")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown module (length=5)");
        assertThatThrownBy(() -> resolver.checkDeactivation(TENANT, "ghost"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("ghost");
    }

    @Test
    @DisplayName("null or blank module id → IllegalArgumentException, registry never read")
    void missingModuleId() {
        for (String id : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> resolver.planActivation(TENANT, id)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A module id is required");
            assertThatThrownBy(() -> resolver.checkDeactivation(TENANT, id))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(registry.reads).isZero();
    }

    @Test
    @DisplayName("hand-built catalogue: unknown or duplicated requires entry → IllegalStateException")
    void invalidRequiresEntries() {
        ModuleCatalog unknown = new ModuleCatalog(List.of(
                loaded("core", ModuleCategory.CORE, true),
                loaded("lonely", ModuleCategory.BUSINESS, true, List.of("missing"), List.of())));
        assertThatThrownBy(() -> new DependencyResolverService(unknown, new FakeRegistry(unknown),
                new FreeModulesOnlyEligibility()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'lonely' requires 'missing', which is not in the module catalogue");
        ModuleCatalog duplicated = new ModuleCatalog(List.of(
                loaded("core", ModuleCategory.CORE, true),
                loaded("base", ModuleCategory.BUSINESS, true),
                loaded("twice", ModuleCategory.BUSINESS, true, List.of("base", "base"), List.of())));
        assertThatThrownBy(() -> new DependencyResolverService(duplicated, new FakeRegistry(duplicated),
                new FreeModulesOnlyEligibility()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'twice' requires 'base' more than once");
    }

    @Test
    @DisplayName("platform-admin or invalid schema → IllegalArgumentException, registry never read")
    void invalidSchemas() {
        for (String schema : new String[] {"public", "KV_1", "", null}) {
            assertThatThrownBy(() -> resolver.planActivation(schema, "x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resolver.checkDeactivation(schema, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(registry.reads).isZero();
    }

    @Test
    @DisplayName("a cycle in the catalogue graph → IllegalStateException when the resolver builds its graph")
    void cycleRejected() {
        ModuleCatalog cyclic = new ModuleCatalog(List.of(
                loaded("core", ModuleCategory.CORE, true),
                loaded("left", ModuleCategory.BUSINESS, true, List.of("right"), List.of()),
                loaded("right", ModuleCategory.BUSINESS, true, List.of("left"), List.of())));
        assertThatThrownBy(() -> new DependencyResolverService(cyclic, new FakeRegistry(cyclic),
                new FreeModulesOnlyEligibility()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("left");
    }

    @Test
    @DisplayName("default eligibility: free → eligible, paid → not")
    void defaultEligibility() {
        FreeModulesOnlyEligibility eligibility = new FreeModulesOnlyEligibility();
        assertThat(eligibility.check(TENANT, catalog.find("solo").orElseThrow()).eligible()).isTrue();
        assertThat(eligibility.check(TENANT, catalog.find("paid").orElseThrow()).eligible()).isFalse();
    }

    /** Registry fake: core always active, plus the modules activated by the test. */
    private static final class FakeRegistry implements ModuleRegistry {

        private final ModuleCatalog catalog;
        private final Set<String> active = new TreeSet<>(Set.of(ModuleManifest.CORE_ID));
        private int reads;

        FakeRegistry(ModuleCatalog catalog) {
            this.catalog = catalog;
        }

        void activate(String... ids) {
            active.addAll(List.of(ids));
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
            reads++;
            return Set.copyOf(active);
        }

        @Override
        public boolean isKnownTenant(String tenantSchema) {
            return true;
        }

        @Override
        public boolean isActive(String tenantSchema, String moduleId) {
            return active.contains(moduleId);
        }

        @Override
        public void evict(String tenantSchema) {
        }
    }
}
