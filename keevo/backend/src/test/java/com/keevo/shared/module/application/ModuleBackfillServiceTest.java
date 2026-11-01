package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.module.ModuleBackfill;
import com.keevo.shared.module.ModuleBackfill.BackfillReport;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.PlanModuleRowSync.SyncResult;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.keevo.shared.module.TestModuleCatalogs.named;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 16.5 — {@link ModuleBackfillService} per-tenant failure isolation: a provisioning failure of one module and
 * a row-sync failure of another tenant are both reported (tenant code, module by display name), and the tenants
 * after them are still synced and provisioned; the lock is always released; a held lock answers
 * {@code MODULE_BACKFILL_RUNNING}.
 */
class ModuleBackfillServiceTest {

    private static final LiveTenant T1 = new LiveTenant(UUID.randomUUID(), "KV-AAA111", "kv_aaa111", true);
    private static final LiveTenant T2 = new LiveTenant(UUID.randomUUID(), "KV-BBB222", "kv_bbb222", true);
    private static final LiveTenant T3 = new LiveTenant(UUID.randomUUID(), "KV-CCC333", "kv_ccc333", true);

    private final ModuleCatalog catalog = new ModuleCatalog(List.of(
            named("core", "Socle", ModuleCategory.CORE, true, List.of()),
            named("crm", "Clients et fournisseurs", ModuleCategory.BUSINESS, true, List.of()),
            named("invoicing", "Facturation", ModuleCategory.PREMIUM, false, List.of())));
    private final ModuleRegistry registry = mock(ModuleRegistry.class);
    private final PlanModuleRowSync rowSync = mock(PlanModuleRowSync.class);
    private final PlanModuleRowStore rows = mock(PlanModuleRowStore.class);
    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);
    private final ModuleMigrationPort migrations = mock(ModuleMigrationPort.class);
    private final LockProvider lockProvider = mock(LockProvider.class);
    private final SimpleLock lock = mock(SimpleLock.class);
    private ModuleBackfillService service;

    @BeforeEach
    void setUp() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
        when(rows.liveTenants()).thenReturn(List.of(T1, T2, T3));
        when(rows.findRows(any())).thenReturn(Map.of());
        when(migrations.hasMigrations("crm")).thenReturn(true);
        when(migrations.hasMigrations("invoicing")).thenReturn(true);
        when(migrations.latestVersion(anyString())).thenReturn("1");
        when(registry.moduleStates(anyString())).thenReturn(Map.of("core", ModuleState.ENABLED,
                "crm", ModuleState.ENABLED, "invoicing", ModuleState.ENABLED));
        when(provisioner.provision(anyString(), anyString()))
                .thenAnswer(inv -> new ProvisioningResult(inv.getArgument(1), null, "1", 1));
        service = new ModuleBackfillService(catalog, registry, rowSync, rows, provisioner, migrations, lockProvider,
                Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("provisioning failure (T1/invoicing) and sync failure (T2) reported; T3 still synced and provisioned")
    void failuresAreIsolated() {
        when(rowSync.sync(T1.id(), T1.tenantSchema())).thenReturn(new SyncResult(1, 0, 0, 0));
        when(rowSync.sync(T2.id(), T2.tenantSchema())).thenThrow(new IllegalStateException("boom"));
        when(rowSync.sync(T3.id(), T3.tenantSchema())).thenReturn(new SyncResult(1, 0, 0, 0));
        when(provisioner.provision(T1.tenantSchema(), "invoicing")).thenThrow(new IllegalStateException("ddl"));

        BackfillReport report = service.run(false);

        assertThat(report.failures()).extracting(ModuleBackfill.Failure::tenantCode)
                .containsExactly(T1.code(), T2.code());
        assertThat(report.failures().get(0).reason()).contains("« Facturation »").doesNotContain("invoicing");
        assertThat(report.failures().get(1).reason()).doesNotContain("boom");
        assertThat(report.tenantsProcessed()).isEqualTo(2);
        assertThat(report.rowsInserted()).isEqualTo(2);
        assertThat(report.modulesProvisioned()).isEqualTo(3); // T1 crm, T3 crm + invoicing
        assertThat(report.tenants()).extracting(ModuleBackfill.TenantChange::tenantCode)
                .containsExactly(T1.code(), T3.code());
        assertThat(report.tenants().get(1).provisioned()).containsExactly("Clients et fournisseurs", "Facturation");
        verify(provisioner).provision(T3.tenantSchema(), "crm");
        verify(provisioner).provision(T3.tenantSchema(), "invoicing");
        verify(provisioner, never()).provision(eq(T2.tenantSchema()), anyString());
        verify(lock).unlock();
    }

    @Test
    @DisplayName("lock held by another run → MODULE_BACKFILL_RUNNING, nothing synced")
    void lockHeld() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(true)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getDomainCode()).isEqualTo("MODULE_BACKFILL_RUNNING"));
        verify(rowSync, never()).preview(any(), anyString());
    }
}
