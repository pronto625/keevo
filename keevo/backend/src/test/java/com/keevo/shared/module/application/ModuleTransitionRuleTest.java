package com.keevo.shared.module.application;

import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Row;
import com.keevo.shared.module.application.port.TenantModuleStatePort.TenantRows;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.keevo.shared.module.TestModuleCatalogs.testCatalog;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.10a — transition rule of paid modules without a {@code tenant_modules} row: ENABLED iff the tenant's
 * plan is PREMIUM, or a PREMIUM_TRIAL not yet expired (subscription ACTIVE); a row always wins; free modules keep
 * the 13.2 rule. Test catalogue: {@code invoicing} is the paid module.
 */
class ModuleTransitionRuleTest {

    private static final String TENANT = "kv_abc123";
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private final Map<String, Row> rows = new HashMap<>();
    private Plan plan = Plan.NONE;

    private final TenantModuleStatePort port = new TenantModuleStatePort() {
        @Override
        public Optional<Map<String, Row>> findRows(String tenantSchema) {
            throw new AssertionError("the registry reads rows and plan together");
        }

        @Override
        public Optional<TenantRows> findTenantRows(String tenantSchema) {
            return TENANT.equals(tenantSchema) ? Optional.of(new TenantRows(rows, plan)) : Optional.empty();
        }
    };

    private ModuleState paidState() {
        return new ModuleRegistryService(testCatalog(), port, new RequestScopedActiveModulesCache(),
                Clock.fixed(NOW, ZoneOffset.UTC)).moduleState(TENANT, "invoicing");
    }

    @Test
    @DisplayName("Free plan / no subscription → paid module DISABLED, free modules ENABLED")
    void freePlan() {
        plan = new Plan("FREE", "ACTIVE", null);
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
        plan = Plan.NONE;
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
        assertThat(new ModuleRegistryService(testCatalog(), port, new RequestScopedActiveModulesCache(),
                Clock.fixed(NOW, ZoneOffset.UTC)).moduleState(TENANT, "crm")).isEqualTo(ModuleState.ENABLED);
    }

    @Test
    @DisplayName("Premium plan (ACTIVE) → ENABLED until its expires_at (or without one)")
    void premiumPlan() {
        plan = new Plan("PREMIUM", "ACTIVE", NOW.plus(Duration.ofDays(30)));
        assertThat(paidState()).isEqualTo(ModuleState.ENABLED);
        plan = new Plan("PREMIUM", "ACTIVE", null);
        assertThat(paidState()).isEqualTo(ModuleState.ENABLED);
    }

    @Test
    @DisplayName("PREMIUM_TRIAL: ENABLED before its end; at / after it READ_ONLY for 7 days (exclusive), then DISABLED")
    void trial() {
        plan = new Plan("PREMIUM_TRIAL", "ACTIVE", NOW.plusSeconds(1));
        assertThat(paidState()).isEqualTo(ModuleState.ENABLED);
        plan = new Plan("PREMIUM_TRIAL", "ACTIVE", NOW);
        assertThat(paidState()).isEqualTo(ModuleState.READ_ONLY);
        plan = new Plan("PREMIUM_TRIAL", "ACTIVE", NOW.minus(Duration.ofDays(1)));
        assertThat(paidState()).isEqualTo(ModuleState.READ_ONLY);
        plan = new Plan("PREMIUM_TRIAL", "ACTIVE", NOW.minus(Duration.ofDays(7)).plusSeconds(1));
        assertThat(paidState()).isEqualTo(ModuleState.READ_ONLY);
        plan = new Plan("PREMIUM_TRIAL", "ACTIVE", NOW.minus(Duration.ofDays(7)));
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("PREMIUM honours expires_at in real time like the trial: READ_ONLY 7 days, then DISABLED")
    void premiumExpiry() {
        plan = new Plan("PREMIUM", "ACTIVE", NOW.minus(Duration.ofHours(1)));
        assertThat(paidState()).isEqualTo(ModuleState.READ_ONLY);
        plan = new Plan("PREMIUM", "EXPIRED", NOW.minus(Duration.ofDays(3)));
        assertThat(paidState()).isEqualTo(ModuleState.READ_ONLY);
        plan = new Plan("PREMIUM", "ACTIVE", NOW.minus(Duration.ofDays(8)));
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("FREE after the nightly EXPIRY downgrade: READ_ONLY for 7 days from the downgrade, then DISABLED")
    void graceAfterExpiryDowngrade() {
        plan = new Plan("FREE", "ACTIVE", null, NOW.minus(Duration.ofDays(2)), false);
        assertThat(paidState()).isEqualTo(ModuleState.READ_ONLY);
        plan = new Plan("FREE", "ACTIVE", null, NOW.minus(Duration.ofDays(7)), false);
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
        plan = new Plan("FREE", "ACTIVE", null, null, false); // admin downgrade / always Free: no grace
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("Plan unreadable → paid modules granted (ENABLED) for the request; free modules unaffected")
    void unreadablePlanGrants() {
        plan = Plan.UNREADABLE;
        assertThat(paidState()).isEqualTo(ModuleState.ENABLED);
        rows.put("invoicing", Row.of(TenantModuleStatus.DISABLED));
        assertThat(paidState()).as("a row still wins").isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("A SUSPENDED subscription grants nothing (no grace), even PREMIUM")
    void inactiveSubscription() {
        plan = new Plan("PREMIUM", "SUSPENDED", null);
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
        plan = new Plan("PREMIUM", "SUSPENDED", NOW.minus(Duration.ofDays(1)));
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
        plan = new Plan("PREMIUM", "EXPIRED", null);
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("Rows win: ACTIVE row on a Free tenant → ENABLED; DISABLED row on a Premium tenant → DISABLED")
    void rowsWin() {
        plan = new Plan("FREE", "ACTIVE", null);
        rows.put("invoicing", Row.of(TenantModuleStatus.ACTIVE));
        assertThat(paidState()).isEqualTo(ModuleState.ENABLED);

        plan = new Plan("PREMIUM", "ACTIVE", null);
        rows.put("invoicing", Row.of(TenantModuleStatus.DISABLED));
        assertThat(paidState()).isEqualTo(ModuleState.DISABLED);

        rows.put("invoicing", new Row(TenantModuleStatus.EXPIRED, NOW.minusSeconds(60), NOW.plusSeconds(60), "PREMIUM"));
        assertThat(paidState()).isEqualTo(ModuleState.READ_ONLY);
    }

    @Test
    @DisplayName("Unknown tenant → paid module DISABLED (only core)")
    void unknownTenant() {
        assertThat(new ModuleRegistryService(testCatalog(), port, new RequestScopedActiveModulesCache(),
                Clock.fixed(NOW, ZoneOffset.UTC)).moduleState("kv_nobody", "invoicing")).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("A port that only answers findRows (no plan) keeps the 13.2 rule: paid DISABLED")
    void defaultPortHasNoPlan() {
        TenantModuleStatePort legacy = schema -> Optional.of(Map.of());
        assertThat(new ModuleRegistryService(testCatalog(), legacy, new RequestScopedActiveModulesCache(),
                Clock.fixed(NOW, ZoneOffset.UTC)).moduleState(TENANT, "invoicing")).isEqualTo(ModuleState.DISABLED);
    }
}
