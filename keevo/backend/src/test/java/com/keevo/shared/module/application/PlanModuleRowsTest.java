package com.keevo.shared.module.application;

import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.PlanModuleRows.Desired;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.keevo.shared.module.TestModuleCatalogs.testCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 16.5 — the paid-module rows derived from a plan ({@link PlanModuleRows}): every branch of the mapping, its
 * exclusive boundaries, and the parity property {@code stateOf(row) == planState(plan)} for every plan × instant.
 */
class PlanModuleRowsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Duration GRACE = Duration.ofDays(7);

    @Test
    @DisplayName("PREMIUM_TRIAL ACTIVE, end in 40 days → TRIAL / PREMIUM, expires = end, grace = end + 7 d")
    void trial() {
        Instant end = NOW.plus(Duration.ofDays(40));
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM_TRIAL", "ACTIVE", end), NOW))
                .isEqualTo(new Desired(TenantModuleStatus.TRIAL, "PREMIUM", end, end.plus(GRACE)));
    }

    @Test
    @DisplayName("PREMIUM ACTIVE → ACTIVE / PREMIUM with the plan dates; without end → no expiry, no grace")
    void premium() {
        Instant end = NOW.plus(Duration.ofDays(200));
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "ACTIVE", end), NOW))
                .isEqualTo(new Desired(TenantModuleStatus.ACTIVE, "PREMIUM", end, end.plus(GRACE)));
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "ACTIVE", null), NOW))
                .isEqualTo(new Desired(TenantModuleStatus.ACTIVE, "PREMIUM", null, null));
    }

    @Test
    @DisplayName("end reached (exclusive: at the end instant) or status EXPIRED → EXPIRED with end + 7 d grace")
    void expired() {
        Instant past = NOW.minus(Duration.ofDays(2));
        Desired expected = new Desired(TenantModuleStatus.EXPIRED, "PREMIUM", past, past.plus(GRACE));
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM_TRIAL", "ACTIVE", past), NOW)).isEqualTo(expected);
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "EXPIRED", past), NOW)).isEqualTo(expected);
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "ACTIVE", NOW), NOW).status())
                .isEqualTo(TenantModuleStatus.EXPIRED);
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "ACTIVE", NOW.plusNanos(1000)), NOW).status())
                .isEqualTo(TenantModuleStatus.ACTIVE);
        // status EXPIRED with a future end: the rule already reads it as read-only — so does the row
        Instant future = NOW.plus(Duration.ofDays(3));
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "EXPIRED", future), NOW))
                .isEqualTo(new Desired(TenantModuleStatus.EXPIRED, "PREMIUM", future, future.plus(GRACE)));
    }

    @Test
    @DisplayName("FREE after an EXPIRY downgrade → EXPIRED anchored on the downgrade instant")
    void downgradedInGrace() {
        Instant downgrade = NOW.minus(Duration.ofDays(3));
        assertThat(PlanModuleRows.rowFor(new Plan("FREE", "ACTIVE", null, downgrade, false), NOW))
                .isEqualTo(new Desired(TenantModuleStatus.EXPIRED, "PREMIUM", downgrade, downgrade.plus(GRACE)));
    }

    @Test
    @DisplayName("plain FREE, no subscription, SUSPENDED, EXPIRED without end, unknown plan → DISABLED / FREE")
    void disabled() {
        Desired disabled = new Desired(TenantModuleStatus.DISABLED, "FREE", null, null);
        assertThat(PlanModuleRows.rowFor(new Plan("FREE", "ACTIVE", null), NOW)).isEqualTo(disabled);
        assertThat(PlanModuleRows.rowFor(Plan.NONE, NOW)).isEqualTo(disabled);
        assertThat(PlanModuleRows.rowFor(null, NOW)).isEqualTo(disabled);
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "SUSPENDED", NOW.plus(Duration.ofDays(9))), NOW))
                .isEqualTo(disabled);
        assertThat(PlanModuleRows.rowFor(new Plan("PREMIUM", "EXPIRED", null), NOW)).isEqualTo(disabled);
        assertThat(PlanModuleRows.rowFor(new Plan("GOLD", "ACTIVE", null), NOW)).isEqualTo(disabled);
    }

    @Test
    @DisplayName("unreadable plan → no row can be derived")
    void unreadable() {
        assertThatThrownBy(() -> PlanModuleRows.rowFor(Plan.UNREADABLE, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("compute: one row per paid tenant module — the 8 paid modules of the shipped catalogue, never free, "
            + "core nor platform")
    void computeOnShippedCatalogue() {
        ModuleCatalog shipped = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        Map<String, Desired> rows = PlanModuleRows.compute(new Plan("PREMIUM_TRIAL", "ACTIVE",
                NOW.plus(Duration.ofDays(40))), NOW, shipped.manifests());
        assertThat(rows.keySet()).containsExactlyInAnyOrder("advanced-reports", "alerts", "counting", "exports",
                "invoicing", "multi-store", "profitability", "report-config");
        assertThat(rows.values()).allMatch(r -> r.status() == TenantModuleStatus.TRIAL);
        assertThat(PlanModuleRows.compute(Plan.NONE, NOW, testCatalog().manifests()).keySet())
                .containsExactly("invoicing");
    }

    @Test
    @DisplayName("parity: for every plan and every instant around its boundaries, stateOf(row) == planState(plan)")
    void parity() {
        ModuleManifest paid = testCatalog().find("invoicing").orElseThrow();
        List<Plan> plans = new ArrayList<>();
        List<Instant> anchors = List.of(NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(7)),
                NOW.minus(Duration.ofDays(3)), NOW, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(200)));
        for (String type : new String[]{"FREE", "PREMIUM_TRIAL", "PREMIUM", null}) {
            for (String status : new String[]{"ACTIVE", "EXPIRED", "SUSPENDED", null}) {
                plans.add(new Plan(type, status, null));
                for (Instant anchor : anchors) {
                    plans.add(new Plan(type, status, anchor));
                    plans.add(new Plan(type, status, null, anchor, false));
                    plans.add(new Plan(type, status, anchor, anchor.minus(Duration.ofDays(1)), false));
                }
            }
        }
        List<Instant> instants = new ArrayList<>();
        for (Instant anchor : anchors) {
            for (Duration offset : List.of(Duration.ZERO, Duration.ofNanos(1000), Duration.ofNanos(-1000),
                    GRACE, GRACE.minusNanos(1000), GRACE.plusNanos(1000), Duration.ofDays(1), Duration.ofDays(30))) {
                instants.add(anchor.plus(offset));
            }
        }
        int checked = 0;
        for (Plan plan : plans) {
            for (Instant writtenAt : List.of(NOW, NOW.minus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(5)))) {
                Desired desired = PlanModuleRows.rowFor(plan, writtenAt);
                Row row = new Row(desired.status(), desired.expiresAt(), desired.graceUntil(), desired.tier());
                // the row is only written when it is computed; parity holds from that instant on (AC 2: the trial
                // end passing later turns it read-only then disabled exactly as the rule would)
                for (Instant at : instants) {
                    if (at.isBefore(writtenAt)) {
                        continue;
                    }
                    ModuleState fromRow = ModuleStateRules.stateOf(paid, Optional.of(Map.of(paid.id(), row)),
                            Plan.NONE, at);
                    ModuleState fromPlan = ModuleStateRules.planState(plan, at);
                    assertThat(fromRow).as("plan %s written at %s, read at %s", plan, writtenAt, at)
                            .isEqualTo(fromPlan);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(1000);
    }
}
