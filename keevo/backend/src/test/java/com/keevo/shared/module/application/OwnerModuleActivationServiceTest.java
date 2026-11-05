package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.module.ModuleOwnerActivationChangedEvent;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.keevo.shared.module.application.OwnerModuleActivationTestSupport.SCHEMA;
import static com.keevo.shared.module.application.OwnerModuleActivationTestSupport.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 19.13 — the owner toggle: every row of the spec's I/O matrix on in-memory fakes. */
@DisplayName("19.13 — OwnerModuleActivationService (owner toggle matrix)")
class OwnerModuleActivationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    private final OwnerModuleActivationTestSupport fake = new OwnerModuleActivationTestSupport();
    private final List<Object> events = new ArrayList<>();
    private final List<String> locks = new ArrayList<>();
    private ModuleRegistryService registry;
    private OwnerModuleActivationService service;

    @BeforeEach
    void setUp() {
        ModuleCatalog catalog = OwnerModuleActivationTestSupport.catalog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        registry = new ModuleRegistryService(catalog, fake.statePort(), new RequestScopedActiveModulesCache(), clock);
        service = new OwnerModuleActivationService(catalog, registry, fake.ownerStore(), locks::add, events::add,
                clock);
        fake.inPlan("invoicing");
        fake.inPlan("counting");
        fake.inPlan("multi-store");
    }

    private DomainException refused(String moduleId, boolean activated) {
        try {
            service.setActivation(SCHEMA, moduleId, activated, OWNER);
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    @DisplayName("disable then re-enable invoicing: saved, locked per tenant, one event each, state follows")
    void disableAndEnable() {
        assertThat(service.setActivation(SCHEMA, "invoicing", false, OWNER)).isTrue();
        assertThat(locks).containsExactly(OwnerModuleActivationService.LOCK_PREFIX + TENANT_ID);
        assertThat(fake.ownerRows).containsEntry("invoicing", false);
        assertThat(events).singleElement().isInstanceOfSatisfying(ModuleOwnerActivationChangedEvent.class, e -> {
            assertThat(e.tenantId()).isEqualTo(SCHEMA);
            assertThat(e.tenantUuid()).isEqualTo(TENANT_ID);
            assertThat(e.moduleId()).isEqualTo("invoicing");
            assertThat(e.moduleDisplayName()).isEqualTo("Facturation");
            assertThat(e.activated()).isFalse();
            assertThat(e.actorId()).isEqualTo(OWNER);
        });
        assertThat(registry.moduleState(SCHEMA, "invoicing").name()).isEqualTo("DISABLED");

        assertThat(service.setActivation(SCHEMA, "invoicing", true, OWNER)).isTrue();
        assertThat(fake.ownerRows).containsEntry("invoicing", true);
        assertThat(events).hasSize(2);
        assertThat(registry.moduleState(SCHEMA, "invoicing").name()).isEqualTo("ENABLED");
    }

    @Test
    @DisplayName("same value (no row = activated; row false + false) → no write, no event")
    void sameValue() {
        assertThat(service.setActivation(SCHEMA, "invoicing", true, OWNER)).isFalse();
        fake.ownerRows.put("exports", false);
        fake.inPlan("exports");
        assertThat(service.setActivation(SCHEMA, "exports", false, OWNER)).isFalse();
        assertThat(fake.upserts).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("not in plan → MODULE_NOT_IN_PLAN (also when the value would be unchanged)")
    void notInPlan() {
        DomainException e = refused("exports", true);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODULE_NOT_IN_PLAN);
        assertThat(e.getUserMessage()).isEqualTo("Ce module n'est pas compris dans votre plan : voyez Mon plan");
        assertThat(refused("exports", false).getErrorCode()).isEqualTo(ErrorCode.MODULE_NOT_IN_PLAN);
        assertThat(fake.upserts).isEmpty();
    }

    @Test
    @DisplayName("core / sync / store / messaging → MODULE_NOT_TOGGLEABLE; unknown and platform → MODULE_NOT_FOUND")
    void notToggleableAndUnknown() {
        for (String id : List.of("core", "sync", "store", "messaging")) {
            DomainException e = refused(id, false);
            assertThat(e.getErrorCode()).as(id).isEqualTo(ErrorCode.MODULE_NOT_TOGGLEABLE);
            assertThat(e.getUserMessage()).isEqualTo("Ce module ne peut pas être désactivé");
        }
        for (String id : List.of("foo", "admin", "")) {
            assertThat(refused(id, true).getErrorCode()).as(id).isEqualTo(ErrorCode.MODULE_NOT_FOUND);
        }
        assertThat(locks).isEmpty();
    }

    @Test
    @DisplayName("disable inventory while pos / counting / multi-store are activated → MODULE_HAS_DEPENDENTS with "
            + "their names; once they are disabled, inventory can be disabled")
    void dependents() {
        DomainException e = refused("inventory", false);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODULE_HAS_DEPENDENTS);
        assertThat(e.getUserMessage()).isEqualTo(
                "Désactivez d'abord : Inventaire assisté, Multi-boutiques et transferts, Point de vente");

        service.setActivation(SCHEMA, "pos", false, OWNER);
        service.setActivation(SCHEMA, "counting", false, OWNER);
        assertThat(refused("inventory", false).getUserMessage())
                .isEqualTo("Désactivez d'abord : Multi-boutiques et transferts");
        service.setActivation(SCHEMA, "multi-store", false, OWNER);
        assertThat(service.setActivation(SCHEMA, "inventory", false, OWNER)).isTrue();
    }

    @Test
    @DisplayName("a dependent outside the plan does not block")
    void dependentOutsideThePlan() {
        fake.planRows.clear(); // counting, multi-store outside the plan
        service.setActivation(SCHEMA, "pos", false, OWNER);
        assertThat(service.setActivation(SCHEMA, "inventory", false, OWNER)).isTrue();
    }

    @Test
    @DisplayName("activate pos while inventory is owner-disabled → MODULE_REQUIREMENT_DISABLED « Activez d'abord : … »")
    void requirementDisabled() {
        fake.ownerRows.put("pos", false);
        fake.ownerRows.put("counting", false);
        fake.ownerRows.put("multi-store", false);
        fake.ownerRows.put("inventory", false);
        DomainException e = refused("pos", true);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODULE_REQUIREMENT_DISABLED);
        assertThat(e.getUserMessage()).isEqualTo("Activez d'abord : inventory");

        service.setActivation(SCHEMA, "inventory", true, OWNER);
        assertThat(service.setActivation(SCHEMA, "pos", true, OWNER)).isTrue();
    }

    @Test
    @DisplayName("a read-only module (grace) is in the plan: it can be disabled")
    void readOnlyInPlan() {
        fake.readOnly("exports", NOW);
        assertThat(service.setActivation(SCHEMA, "exports", false, OWNER)).isTrue();
    }

    @Test
    @DisplayName("unknown tenant → TENANT_NOT_FOUND")
    void unknownTenant() {
        assertThatThrownBy(() -> service.setActivation("kv_zzz999", "invoicing", false, OWNER))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND));
    }
}
