package com.keevo.shared.module.application;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.8 — a paid module without a {@code tenant_modules} row follows the live profile of the tenant's plan type
 * in the registry (no-row transition rule), shipped catalogue.
 */
class ModuleRegistryProfileTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String SCHEMA = "kv_prof01";

    private final Map<PlanType, PlanProfile> stored = new java.util.HashMap<>();
    private final PlanProfiles profiles = type -> stored.getOrDefault(type, com.keevo.shared.capability.entitlement.TestPlanProfiles.profile(type));
    private final AtomicReference<TenantModuleStatePort.Plan> plan = new AtomicReference<>();

    /** A tenant without any {@code tenant_modules} row, on the plan held by {@link #plan}. */
    private final TenantModuleStatePort states = new TenantModuleStatePort() {
        @Override
        public Optional<Map<String, Row>> findRows(String tenantSchema) {
            return Optional.of(Map.of());
        }

        @Override
        public Optional<TenantRows> findTenantRows(String tenantSchema) {
            return Optional.of(new TenantRows(Map.of(), plan.get()));
        }
    };

    private final ModuleRegistryService registry = new ModuleRegistryService(
            new ModuleCatalog(new PathMatchingResourcePatternResolver()), states,
            new RequestScopedActiveModulesCache(), Clock.fixed(NOW, ZoneOffset.UTC), profiles);

    @Test
    @DisplayName("FREE profile granting exports → exports ENABLED without a row; other paid modules DISABLED")
    void freeProfileGrantsExports() {
        plan.set(new TenantModuleStatePort.Plan("FREE", "ACTIVE", null));
        registry.evict(SCHEMA);
        assertThat(registry.moduleState(SCHEMA, "exports")).isEqualTo(ModuleState.DISABLED);

        stored.put(PlanType.FREE, PlanProfile.of(PlanType.FREE, 1, 500, 2, Set.of("exports")));
        registry.evict(SCHEMA);

        assertThat(registry.moduleState(SCHEMA, "exports")).isEqualTo(ModuleState.ENABLED);
        assertThat(registry.moduleState(SCHEMA, "invoicing")).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("PREMIUM profile withholding invoicing → invoicing DISABLED without a row; a paid module without a "
            + "decision stays ENABLED")
    void premiumProfileWithoutInvoicing() {
        plan.set(new TenantModuleStatePort.Plan("PREMIUM", "ACTIVE", NOW.plusSeconds(86_400 * 30L)));
        stored.put(PlanType.PREMIUM, PlanProfile.of(PlanType.PREMIUM, null, null, null, Set.of("exports"),
                Set.of("invoicing")));
        registry.evict(SCHEMA);

        assertThat(registry.moduleState(SCHEMA, "invoicing")).isEqualTo(ModuleState.DISABLED);
        assertThat(registry.moduleState(SCHEMA, "exports")).isEqualTo(ModuleState.ENABLED);
        assertThat(registry.moduleState(SCHEMA, "counting")).isEqualTo(ModuleState.ENABLED);
    }
}
