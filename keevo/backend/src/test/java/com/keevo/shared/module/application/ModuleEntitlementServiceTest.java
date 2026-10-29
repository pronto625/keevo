package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Row;
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

import static com.keevo.shared.module.TestModuleCatalogs.ALL;
import static com.keevo.shared.module.TestModuleCatalogs.FREE;
import static com.keevo.shared.module.TestModuleCatalogs.testCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 13.5 — {@link ModuleEntitlementService} over the real registry (fake port, fixed clock). */
class ModuleEntitlementServiceTest {

    private static final String TENANT = "kv_ent123";
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private final Map<String, Row> rows = new HashMap<>();
    private final TenantModuleStatePort port = schema -> TENANT.equals(schema) ? Optional.of(Map.copyOf(rows))
            : Optional.empty();
    private final ModuleEntitlementService entitlements = new ModuleEntitlementService(new ModuleRegistryService(
            testCatalog(), port, new RequestScopedActiveModulesCache(), Clock.fixed(NOW, ZoneOffset.UTC)));

    private static String domainCode(Runnable call) {
        try {
            call.run();
            return null;
        } catch (DomainException e) {
            return e.getDomainCode();
        }
    }

    @Test
    @DisplayName("free, no row → enabled and writable")
    void freeNoRow() {
        assertThat(entitlements.state(TENANT, "crm")).isEqualTo(ModuleState.ENABLED);
        assertThat(entitlements.isEnabled(TENANT, "crm")).isTrue();
        assertThat(entitlements.isReadOnly(TENANT, "crm")).isFalse();
        assertThatCode(() -> entitlements.assertWritable(TENANT, "crm")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("paid, no row → MODULE_NOT_ENTITLED on both asserts; message does not echo the module id")
    void paidNoRow() {
        assertThat(entitlements.isEnabled(TENANT, "invoicing")).isFalse();
        assertThat(domainCode(() -> entitlements.assertEnabled(TENANT, "invoicing"))).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(domainCode(() -> entitlements.assertWritable(TENANT, "invoicing"))).isEqualTo("MODULE_NOT_ENTITLED");
        assertThatThrownBy(() -> entitlements.assertEnabled(TENANT, "invoicing")).hasMessageNotContaining("invoicing");
    }

    @Test
    @DisplayName("ACTIVE expired 1 h ago, grace +7 d → read OK, write MODULE_READ_ONLY")
    void readOnly() {
        rows.put("invoicing", new Row(TenantModuleStatus.ACTIVE, NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(7)), "PREMIUM"));
        assertThat(entitlements.isEnabled(TENANT, "invoicing")).isTrue();
        assertThat(entitlements.isReadOnly(TENANT, "invoicing")).isTrue();
        assertThatCode(() -> entitlements.assertEnabled(TENANT, "invoicing")).doesNotThrowAnyException();
        assertThat(domainCode(() -> entitlements.assertWritable(TENANT, "invoicing"))).isEqualTo("MODULE_READ_ONLY");
        assertThat(entitlements.enabledModules(TENANT)).contains("invoicing");
    }

    @Test
    @DisplayName("ACTIVE, grace ended → MODULE_NOT_ENTITLED; DISABLED row → not enabled")
    void graceEndedAndDisabled() {
        rows.put("invoicing", new Row(TenantModuleStatus.ACTIVE, NOW.minus(Duration.ofDays(8)),
                NOW.minus(Duration.ofDays(1)), "PREMIUM"));
        rows.put("pos", Row.of(TenantModuleStatus.DISABLED));
        assertThat(domainCode(() -> entitlements.assertEnabled(TENANT, "invoicing"))).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(entitlements.isEnabled(TENANT, "pos")).isFalse();
        assertThat(entitlements.enabledModules(TENANT)).doesNotContain("invoicing", "pos").contains("core");
    }

    @Test
    @DisplayName("enabledModules: free set without rows; unknown tenant → core only; unknown module id → not enabled")
    void enabledModules() {
        assertThat(entitlements.enabledModules(TENANT)).containsExactlyElementsOf(FREE);
        assertThat(entitlements.enabledModules("kv_nobody")).containsExactly("core");
        assertThat(entitlements.isEnabled(TENANT, "ghost")).isFalse();
        assertThat(entitlements.isEnabled(TENANT, null)).isFalse();
    }

    @Test
    @DisplayName("platform-admin schema: every installed module ENABLED (super-admin not gated); unknown id still refused")
    void platformAdmin() {
        assertThat(entitlements.enabledModules("public")).containsExactlyElementsOf(ALL);
        assertThatCode(() -> entitlements.assertWritable("public", "invoicing")).doesNotThrowAnyException();
        assertThat(domainCode(() -> entitlements.assertEnabled("public", "ghost"))).isEqualTo("MODULE_NOT_ENTITLED");
    }

    @Test
    @DisplayName("invalid schema string → IllegalArgumentException")
    void invalidSchema() {
        assertThatThrownBy(() -> entitlements.isEnabled("KV_1", "crm")).isInstanceOf(IllegalArgumentException.class);
    }
}
