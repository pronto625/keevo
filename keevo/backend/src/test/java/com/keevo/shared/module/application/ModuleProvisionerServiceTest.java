package com.keevo.shared.module.application;

import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.ProvisioningReport;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort.LiveTenant;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 13.9 — {@link ModuleProvisionerService#provisionActiveModules} isolates each module (its own transaction,
 * one failure does not stop the others) and {@code provisionAll} skips the platform tenant.
 */
class ModuleProvisionerServiceTest {

    private static final String SCHEMA = "kv_iso001";

    private final ModuleMigrationPort migrations = mock(ModuleMigrationPort.class);
    private final TenantModuleProvisioningPort rows = mock(TenantModuleProvisioningPort.class);
    /** A known tenant without any row: free modules are active (free rule). */
    private final TenantModuleStatePort states = schema -> Optional.of(Map.of());
    private final List<String> transactions = new ArrayList<>();

    /** Records one entry per isolated unit of work, runs it without a real transaction. */
    private final TransactionOperations recording = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            transactions.add("tx");
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
        }
    };

    private ModuleProvisionerService service() {
        ModuleCatalog catalog = TestModuleCatalogs.provisioningCatalog();
        ModuleRegistryService registry = new ModuleRegistryService(catalog, states,
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
        return new ModuleProvisionerService(catalog, registry, states, rows, migrations,
                KeevoModuleDirectory.empty(catalog), recording);
    }

    @Test
    @DisplayName("provisionActiveModules: each free module in its own transaction; a failing one does not stop the others")
    void isolation() {
        when(migrations.hasMigrations(anyString())).thenAnswer(inv -> List.of("provtest", "provbroken", "provbatch")
                .contains(inv.<String>getArgument(0)));
        when(migrations.migrate(eq(SCHEMA), eq("provbroken"), any()))
                .thenThrow(new ModuleProvisioningException("provbroken", SCHEMA, "42601", null));
        when(migrations.migrate(eq(SCHEMA), eq("provtest"), any()))
                .thenReturn(new ModuleMigrationPort.Outcome(null, "2", 2));
        when(migrations.migrate(eq(SCHEMA), eq("provbatch"), any()))
                .thenReturn(new ModuleMigrationPort.Outcome(null, "1", 1));
        when(rows.insertActiveFree(anyString(), anyString(), anyString())).thenReturn(1);
        when(rows.liveTenants()).thenReturn(List.of(new LiveTenant("KV-ISO001", SCHEMA, true)));

        List<ProvisioningResult> results = service().provisionActiveModules(SCHEMA);

        assertThat(results).extracting(ProvisioningResult::moduleId).containsExactly("provbatch", "provtest");
        verify(migrations).migrate(eq(SCHEMA), eq("provbroken"), any()); // attempted, failed, the others went on
        assertThat(transactions).hasSize(3);
        verify(rows, never()).insertActiveFree(SCHEMA, "provbroken", null);
    }

    @Test
    @DisplayName("provisionAll skips the platform tenant (schema public) — never reported as a failure")
    void platformTenantSkipped() {
        when(migrations.hasMigrations("provtest")).thenReturn(true);
        when(migrations.migrate(eq(SCHEMA), eq("provtest"), any()))
                .thenReturn(new ModuleMigrationPort.Outcome(null, "2", 2));
        when(rows.insertActiveFree(anyString(), anyString(), anyString())).thenReturn(1);
        when(rows.liveTenants()).thenReturn(List.of(new LiveTenant("KV-ADMIN", "public", true),
                new LiveTenant("KV-ISO001", SCHEMA, true)));

        ProvisioningReport report = service().provisionAll("provtest");

        assertThat(report.skipped()).containsExactly("KV-ADMIN");
        assertThat(report.failures()).isEmpty();
        assertThat(report.provisioned()).containsExactly("KV-ISO001");
        verify(migrations, never()).migrate(eq("public"), anyString(), any());
    }

    // ── Story 14.1 ───────────────────────────────────────────────────────────────────────────────────────────

    /** A known tenant without rows on a PREMIUM plan: paid modules active through the transition rule. */
    private ModuleProvisionerService premiumService() {
        ModuleCatalog catalog = TestModuleCatalogs.provisioningCatalog();
        TenantModuleStatePort premium = new TenantModuleStatePort() {
            @Override
            public Optional<Map<String, Row>> findRows(String tenantSchema) {
                return Optional.of(Map.of());
            }

            @Override
            public Optional<TenantRows> findTenantRows(String tenantSchema) {
                return Optional.of(new TenantRows(Map.of(), new Plan("PREMIUM", "ACTIVE", null)));
            }
        };
        ModuleRegistryService registry = new ModuleRegistryService(catalog, premium,
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
        return new ModuleProvisionerService(catalog, registry, premium, rows, migrations,
                KeevoModuleDirectory.empty(catalog), recording);
    }

    @Test
    @DisplayName("14.1: a paid module without row is provisioned but no tenant_modules row is inserted (never ACTIVE/FREE for ever)")
    void paidModuleNoRowInsertsNothing() {
        when(migrations.hasMigrations("provpaid")).thenReturn(true);
        when(migrations.migrate(eq(SCHEMA), eq("provpaid"), any()))
                .thenReturn(new ModuleMigrationPort.Outcome(null, "1", 1));

        ProvisioningResult result = premiumService().provision(SCHEMA, "provpaid");

        assertThat(result.toVersion()).isEqualTo("1");
        verify(rows, never()).insertActiveFree(anyString(), anyString(), anyString());
        verify(rows, never()).updateProvisionedVersion(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("14.1: a free module without row still gets its ACTIVE/FREE row (13.4 rule unchanged)")
    void freeModuleNoRowStillInserted() {
        when(migrations.hasMigrations("provtest")).thenReturn(true);
        when(migrations.migrate(eq(SCHEMA), eq("provtest"), any()))
                .thenReturn(new ModuleMigrationPort.Outcome(null, "2", 2));
        when(rows.insertActiveFree(SCHEMA, "provtest", "2")).thenReturn(1);

        service().provision(SCHEMA, "provtest");

        verify(rows).insertActiveFree(SCHEMA, "provtest", "2");
    }

    @Test
    @DisplayName("14.1 ensureProvisioned: behind → provisioned once; warm calls do no database work; up to date → no migration")
    void ensureProvisionedCached() {
        when(migrations.hasMigrations("provpaid")).thenReturn(true);
        when(migrations.latestVersion("provpaid")).thenReturn("2");
        when(migrations.schemaExists(anyString())).thenReturn(true);
        when(migrations.currentVersion(SCHEMA, "provpaid")).thenReturn("1");
        when(migrations.migrate(eq(SCHEMA), eq("provpaid"), any()))
                .thenReturn(new ModuleMigrationPort.Outcome("1", "2", 1));
        ModuleProvisionerService service = premiumService();

        service.ensureProvisioned(SCHEMA, "provpaid");
        verify(migrations, org.mockito.Mockito.times(1)).migrate(eq(SCHEMA), eq("provpaid"), any());
        verify(migrations, org.mockito.Mockito.times(1)).currentVersion(SCHEMA, "provpaid");

        org.mockito.Mockito.clearInvocations(migrations, rows);
        service.ensureProvisioned(SCHEMA, "provpaid");
        service.ensureProvisioned(SCHEMA, "provpaid");
        org.mockito.Mockito.verifyNoInteractions(migrations, rows); // warm: a map lookup

        when(migrations.currentVersion("kv_iso002", "provpaid")).thenReturn("2");
        service.ensureProvisioned("kv_iso002", "provpaid");
        verify(migrations, never()).migrate(eq("kv_iso002"), anyString(), any());
    }

    @Test
    @DisplayName("14.1 ensureProvisioned: a legacy module (existing-schema baseline, e.g. crm) is never adopted on first use")
    void ensureProvisionedSkipsLegacyModules() {
        ModuleCatalog catalog = TestModuleCatalogs.provisioningCatalog();
        com.keevo.shared.module.KeevoModule legacy = new com.keevo.shared.module.KeevoModule() {
            @Override
            public String id() {
                return "provtest";
            }

            @Override
            public Optional<com.keevo.shared.module.ExistingSchemaBaseline> existingSchemaBaseline() {
                return Optional.of(new com.keevo.shared.module.ExistingSchemaBaseline("legacy_table", "1"));
            }
        };
        ModuleRegistryService registry = new ModuleRegistryService(catalog, states,
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
        ModuleProvisionerService service = new ModuleProvisionerService(catalog, registry, states, rows, migrations,
                KeevoModuleDirectory.of(catalog, List.of(legacy)), recording);
        when(migrations.hasMigrations("provtest")).thenReturn(true);

        service.ensureProvisioned(SCHEMA, "provtest");

        verify(migrations, never()).currentVersion(anyString(), anyString());
        verify(migrations, never()).migrate(anyString(), anyString(), any());
        org.mockito.Mockito.verifyNoInteractions(rows);
    }

    @Test
    @DisplayName("14.1 ensureProvisioned: platform / invalid schema, unknown module, module without migrations → no-op")
    void ensureProvisionedNoOps() {
        when(migrations.hasMigrations("provempty")).thenReturn(false);
        ModuleProvisionerService service = premiumService();

        service.ensureProvisioned("public", "provpaid");
        service.ensureProvisioned("bad", "provpaid");
        service.ensureProvisioned(SCHEMA, "ghost");
        service.ensureProvisioned(SCHEMA, "provempty");

        verify(migrations, never()).migrate(anyString(), anyString(), any());
        verify(migrations, never()).currentVersion(anyString(), anyString());
    }

    @Test
    @DisplayName("14.1 ensureProvisioned: a failure → SERVICE_UNAVAILABLE (503), not cached — the next call retries")
    void ensureProvisionedFailure() {
        when(migrations.hasMigrations("provpaid")).thenReturn(true);
        when(migrations.latestVersion("provpaid")).thenReturn("1");
        when(migrations.schemaExists(SCHEMA)).thenReturn(true);
        when(migrations.currentVersion(SCHEMA, "provpaid")).thenReturn(null);
        when(migrations.migrate(eq(SCHEMA), eq("provpaid"), any()))
                .thenThrow(new ModuleProvisioningException("provpaid", SCHEMA, "42601", null))
                .thenReturn(new ModuleMigrationPort.Outcome(null, "1", 1));
        when(rows.liveTenants()).thenReturn(List.of(new LiveTenant("KV-ISO001", SCHEMA, true)));
        ModuleProvisionerService service = premiumService();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ensureProvisioned(SCHEMA, "provpaid"))
                .isInstanceOf(com.keevo.shared.domain.exception.DomainException.class)
                .satisfies(e -> assertThat(((com.keevo.shared.domain.exception.DomainException) e).getDomainCode())
                        .isEqualTo("SERVICE_UNAVAILABLE"));
        service.ensureProvisioned(SCHEMA, "provpaid");
        verify(migrations, org.mockito.Mockito.times(2)).migrate(eq(SCHEMA), eq("provpaid"), any());
    }

    @Test
    @DisplayName("14.1 ensureProvisioned: concurrent first calls migrate once")
    void ensureProvisionedConcurrent() throws Exception {
        when(migrations.hasMigrations("provpaid")).thenReturn(true);
        when(migrations.latestVersion("provpaid")).thenReturn("1");
        when(migrations.schemaExists(SCHEMA)).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<String> current = new java.util.concurrent.atomic.AtomicReference<>();
        when(migrations.currentVersion(SCHEMA, "provpaid")).thenAnswer(inv -> current.get());
        when(migrations.migrate(eq(SCHEMA), eq("provpaid"), any())).thenAnswer(inv -> {
            Thread.sleep(50);
            current.set("1");
            return new ModuleMigrationPort.Outcome(null, "1", 1);
        });
        ModuleProvisionerService service = premiumService();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> service.ensureProvisioned(SCHEMA, "provpaid")));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        verify(migrations, org.mockito.Mockito.times(1)).migrate(eq(SCHEMA), eq("provpaid"), any());
    }

    @Test
    @DisplayName("14.1 ensureProvisioned: tenant schema not created yet → nothing done, not cached")
    void ensureProvisionedSchemaMissing() {
        when(migrations.hasMigrations("provpaid")).thenReturn(true);
        when(migrations.latestVersion("provpaid")).thenReturn("1");
        when(migrations.schemaExists(SCHEMA)).thenReturn(false);
        ModuleProvisionerService service = premiumService();

        service.ensureProvisioned(SCHEMA, "provpaid");
        service.ensureProvisioned(SCHEMA, "provpaid");

        verify(migrations, org.mockito.Mockito.times(2)).schemaExists(SCHEMA);
        verify(migrations, never()).migrate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("14.1 ensureProvisioned compares versions the Flyway way (1.10 > 1.9: behind → provisioned)")
    void versionComparison() {
        when(migrations.hasMigrations("provpaid")).thenReturn(true);
        when(migrations.latestVersion("provpaid")).thenReturn("1.10");
        when(migrations.schemaExists(anyString())).thenReturn(true);
        when(migrations.currentVersion(SCHEMA, "provpaid")).thenReturn("1.9");
        when(migrations.currentVersion("kv_iso002", "provpaid")).thenReturn("1.10");
        when(migrations.migrate(eq(SCHEMA), eq("provpaid"), any()))
                .thenReturn(new ModuleMigrationPort.Outcome("1.9", "1.10", 1));
        ModuleProvisionerService service = premiumService();

        service.ensureProvisioned(SCHEMA, "provpaid");
        service.ensureProvisioned("kv_iso002", "provpaid");

        verify(migrations).migrate(eq(SCHEMA), eq("provpaid"), any());
        verify(migrations, never()).migrate(eq("kv_iso002"), anyString(), any());
    }
}
