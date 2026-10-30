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
}
