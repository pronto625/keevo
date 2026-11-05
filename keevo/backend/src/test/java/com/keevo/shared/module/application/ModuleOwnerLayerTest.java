package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static com.keevo.shared.module.application.OwnerModuleActivationTestSupport.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 19.13 — the owner layer over the entitlement state (« usable = entitled ∧ activated »): {@code ModuleStateRules},
 * the registry (states with / without the owner layer, reason) and the entitlement check's refusal code.
 */
@DisplayName("19.13 — owner layer: ModuleStateRules, registry, entitlement refusal")
class ModuleOwnerLayerTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final OwnerModuleActivationTestSupport fake = new OwnerModuleActivationTestSupport();
    private final ModuleCatalog catalog = OwnerModuleActivationTestSupport.catalog();
    private ModuleRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = new ModuleRegistryService(catalog, fake.statePort(), new RequestScopedActiveModulesCache(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ModuleManifest m(String id) {
        return catalog.find(id).orElseThrow();
    }

    @Test
    @DisplayName("withOwnerChoice: ENABLED and READ_ONLY → DISABLED when owner-disabled; DISABLED (not in plan), "
            + "core and non-toggleable modules unaffected")
    void rules() {
        Set<String> off = Set.of("invoicing", "exports", "core", "store", "pos");
        assertThat(ModuleStateRules.withOwnerChoice(m("invoicing"), ModuleState.ENABLED, off))
                .isEqualTo(ModuleState.DISABLED);
        assertThat(ModuleStateRules.withOwnerChoice(m("invoicing"), ModuleState.READ_ONLY, off))
                .isEqualTo(ModuleState.DISABLED);
        assertThat(ModuleStateRules.withOwnerChoice(m("exports"), ModuleState.DISABLED, off))
                .isEqualTo(ModuleState.DISABLED);
        assertThat(ModuleStateRules.withOwnerChoice(m("core"), ModuleState.ENABLED, off))
                .isEqualTo(ModuleState.ENABLED);
        assertThat(ModuleStateRules.withOwnerChoice(m("store"), ModuleState.ENABLED, off))
                .isEqualTo(ModuleState.ENABLED);
        assertThat(ModuleStateRules.withOwnerChoice(m("pos"), ModuleState.ENABLED, Set.of()))
                .isEqualTo(ModuleState.ENABLED);
        assertThat(ModuleStateRules.withOwnerChoice(m("pos"), ModuleState.ENABLED, null))
                .isEqualTo(ModuleState.ENABLED);
    }

    @Test
    @DisplayName("registry: moduleStates applies the owner layer, entitledStates does not; disabledByOwner only for "
            + "an in-plan, owner-disabled, toggleable module")
    void registry() {
        fake.inPlan("invoicing");
        fake.readOnly("counting", NOW);
        fake.ownerRows.put("invoicing", false);
        fake.ownerRows.put("counting", false);
        fake.ownerRows.put("exports", false); // not in plan
        fake.ownerRows.put("store", false);   // not toggleable
        fake.ownerRows.put("pos", true);      // explicitly activated

        assertThat(registry.moduleStates(SCHEMA)).containsEntry("invoicing", ModuleState.DISABLED)
                .containsEntry("counting", ModuleState.DISABLED)
                .containsEntry("exports", ModuleState.DISABLED)
                .containsEntry("store", ModuleState.ENABLED)
                .containsEntry("pos", ModuleState.ENABLED)
                .containsEntry("core", ModuleState.ENABLED);
        assertThat(registry.entitledStates(SCHEMA)).containsEntry("invoicing", ModuleState.ENABLED)
                .containsEntry("counting", ModuleState.READ_ONLY)
                .containsEntry("exports", ModuleState.DISABLED);
        assertThat(registry.ownerDisabledModules(SCHEMA))
                .containsExactlyInAnyOrder("invoicing", "counting", "exports", "store");
        assertThat(registry.disabledByOwner(SCHEMA, "invoicing")).isTrue();
        assertThat(registry.disabledByOwner(SCHEMA, "counting")).isTrue();
        assertThat(registry.disabledByOwner(SCHEMA, "exports")).isFalse();
        assertThat(registry.disabledByOwner(SCHEMA, "store")).isFalse();
        assertThat(registry.disabledByOwner(SCHEMA, "pos")).isFalse();
        assertThat(registry.disabledByOwner(SCHEMA, "foo")).isFalse();
        assertThat(registry.disabledByOwner("kv_zzz999", "invoicing")).isFalse();
        assertThat(registry.isActive(SCHEMA, "invoicing")).isFalse();
        assertThat(registry.activeModules(SCHEMA)).doesNotContain("invoicing", "counting");
    }

    @Test
    @DisplayName("propagation: reporting owner-disabled ⇒ exports DISABLED even with an ACTIVE plan row, reason "
            + "disabledByOwner; its own choice stays activated; re-enabling reporting brings exports back")
    void propagatesThroughRequires() {
        fake.inPlan("exports");
        fake.ownerRows.put("reporting", false);

        assertThat(ModuleStateRules.ownerOff(catalog.manifests(), Set.of("reporting")))
                .containsExactlyInAnyOrder("reporting", "exports");
        assertThat(ModuleStateRules.ownerOff(catalog.manifests(), Set.of("inventory")))
                .containsExactlyInAnyOrder("inventory", "pos", "counting", "multi-store");
        assertThat(registry.moduleState(SCHEMA, "exports")).isEqualTo(ModuleState.DISABLED);
        assertThat(registry.entitledStates(SCHEMA)).containsEntry("exports", ModuleState.ENABLED);
        assertThat(registry.disabledByOwner(SCHEMA, "exports")).isTrue();
        assertThat(registry.ownerDisabledModules(SCHEMA)).containsExactly("reporting");
        assertThatThrownBy(() -> new ModuleEntitlementService(registry).assertWritable(SCHEMA, "exports"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODULE_DISABLED_BY_OWNER));

        fake.ownerRows.put("reporting", true);
        registry.evict(SCHEMA);
        assertThat(registry.moduleState(SCHEMA, "exports")).isEqualTo(ModuleState.ENABLED);
    }

    @Test
    @DisplayName("entitlement: an owner-disabled module answers MODULE_DISABLED_BY_OWNER (read and write); a module "
            + "outside the plan keeps MODULE_NOT_ENTITLED")
    void entitlementRefusal() {
        fake.inPlan("invoicing");
        fake.ownerRows.put("invoicing", false);
        fake.ownerRows.put("exports", false);
        ModuleEntitlementService entitlements = new ModuleEntitlementService(registry);

        for (Runnable call : new Runnable[]{() -> entitlements.assertEnabled(SCHEMA, "invoicing"),
                () -> entitlements.assertWritable(SCHEMA, "invoicing")}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODULE_DISABLED_BY_OWNER);
                assertThat(e.getUserMessage()).isEqualTo(
                        "Ce module est désactivé : le propriétaire peut l'activer dans Paramètres › Mes modules");
            });
        }
        assertThatThrownBy(() -> entitlements.assertWritable(SCHEMA, "exports"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODULE_NOT_ENTITLED));

        fake.ownerRows.put("invoicing", true);
        registry.evict(SCHEMA);
        entitlements.assertWritable(SCHEMA, "invoicing");
    }
}
