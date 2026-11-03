package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.FoundationUnavailableException;
import com.keevo.shared.module.FoundationUpgrade;
import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort.LiveTenant;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 16.12 — foundation tables: {@code ensureFoundation} (request path, cached per JVM) and
 * {@code upgradeFoundation} (boot: set-based detection, only tenants behind provisioned, failures isolated). Test
 * catalogue: {@code core} and the free modules {@code provbatch} (latest 1) and {@code provtest} (latest 2) ship
 * migrations; {@code core} never has a {@code tenant_modules} row (its version comes from its history).
 */
@DisplayName("Foundation tables (Story 16.12)")
class FoundationProvisioningTest {

    private static final String A = "kv_fnda01";
    private static final String B = "kv_fndb02";
    private static final String C = "kv_fndc03";
    private static final String D = "kv_fndd04";
    private static final String E = "kv_fnde05";
    private static final Map<String, String> LATEST = Map.of("core", "1", "provbatch", "1", "provtest", "2");

    private final ModuleMigrationPort migrations = mock(ModuleMigrationPort.class);
    private final TenantModuleProvisioningPort rows = mock(TenantModuleProvisioningPort.class);
    private final TenantModuleStatePort states = schema -> Optional.of(Map.of());
    private ModuleProvisionerService service;

    @BeforeEach
    void setUp() {
        ModuleCatalog catalog = TestModuleCatalogs.provisioningCatalog();
        ModuleRegistryService registry = new ModuleRegistryService(catalog, states,
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
        service = new ModuleProvisionerService(catalog, registry, states, rows, migrations,
                KeevoModuleDirectory.empty(catalog), TransactionOperations.withoutTransaction());
        when(migrations.hasMigrations(anyString())).thenAnswer(inv -> LATEST.containsKey(inv.<String>getArgument(0)));
        when(migrations.latestVersion(anyString())).thenAnswer(inv -> LATEST.get(inv.<String>getArgument(0)));
        when(migrations.schemaExists(anyString())).thenReturn(true);
        when(migrations.migrate(anyString(), anyString())).thenAnswer(inv ->
                new ModuleMigrationPort.Outcome(null, LATEST.get(inv.<String>getArgument(1)), 1));
        when(rows.insertActiveFree(anyString(), anyString(), anyString())).thenReturn(1);
        when(rows.updateProvisionedVersion(anyString(), anyString(), anyString())).thenReturn(1);
    }

    // ── ensureFoundation (request path) ──────────────────────────────────────

    @Test
    @DisplayName("behind tenant: the modules behind are provisioned in module order, then warm calls do no database work")
    void ensureFoundationProvisionsThenCaches() {
        when(migrations.schemaVersions(eq(A), anyCollection())).thenReturn(Map.of("core", "1", "provtest", "1"));

        service.ensureFoundation(A);

        verify(migrations, never()).migrate(eq(A), eq("core"));
        verify(migrations).migrate(eq(A), eq("provbatch"));
        verify(migrations).migrate(eq(A), eq("provtest"));
        verify(rows).insertActiveFree(A, "provbatch", "1");
        clearInvocations(migrations, rows);

        service.ensureFoundation(A);
        service.ensureFoundation(A);

        verifyNoInteractions(migrations, rows);
    }

    @Test
    @DisplayName("up-to-date tenant: two history reads, no migration, cached")
    void ensureFoundationUpToDate() {
        when(migrations.schemaVersions(eq(A), anyCollection())).thenReturn(LATEST);

        service.ensureFoundation(A);
        service.ensureFoundation(A);

        verify(migrations, times(1)).schemaVersions(eq(A), anyCollection());
        verify(migrations, never()).migrate(anyString(), anyString());
    }

    @Test
    @DisplayName("a failure → 503 with Retry-After; calls during the backoff fail at once (no Flyway); after it, retried; backoff doubles")
    void ensureFoundationFailureBacksOff() {
        long[] now = {0};
        service.nanoClock = () -> now[0];
        when(migrations.schemaVersions(eq(A), anyCollection())).thenReturn(Map.of("core", "1", "provtest", "2"));
        when(migrations.migrate(eq(A), eq("provbatch")))
                .thenThrow(new ModuleProvisioningException("provbatch", A, "55P03", null))
                .thenThrow(new ModuleProvisioningException("provbatch", A, "55P03", null))
                .thenReturn(new ModuleMigrationPort.Outcome(null, "1", 1));

        assertThatThrownBy(() -> service.ensureFoundation(A))
                .isInstanceOfSatisfying(FoundationUnavailableException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(e.retryAfterSeconds()).isEqualTo(30);
                });
        now[0] = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        assertThatThrownBy(() -> service.ensureFoundation(A))
                .isInstanceOfSatisfying(FoundationUnavailableException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(20));
        verify(migrations, times(1)).migrate(eq(A), eq("provbatch")); // no new attempt during the backoff

        now[0] = java.util.concurrent.TimeUnit.SECONDS.toNanos(31);
        assertThatThrownBy(() -> service.ensureFoundation(A))
                .isInstanceOfSatisfying(FoundationUnavailableException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(60)); // doubled
        now[0] = java.util.concurrent.TimeUnit.SECONDS.toNanos(100);
        service.ensureFoundation(A);
        verify(migrations, times(3)).migrate(eq(A), eq("provbatch"));
    }

    @Test
    @DisplayName("another provisioning holds the tenant's lock: 503 after a bounded wait, no Flyway")
    void ensureFoundationLockBusy() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(migrations.schemaVersions(eq(B), anyCollection())).thenReturn(Map.of("core", "1", "provtest", "2"));
        when(migrations.migrate(eq(B), eq("provbatch"))).thenAnswer(inv -> {
            inside.countDown();
            release.await();
            return new ModuleMigrationPort.Outcome(null, "1", 1);
        });
        Thread holder = new Thread(() -> service.ensureFoundation(B));
        holder.start();
        inside.await();
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> service.ensureFoundation(B)).isInstanceOf(FoundationUnavailableException.class);
            assertThat(System.nanoTime() - started).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        } finally {
            release.countDown();
            holder.join();
        }
        verify(migrations, times(1)).migrate(eq(B), eq("provbatch"));
    }

    @Test
    @DisplayName("platform / invalid schema, schema not created yet → no-op, not cached")
    void ensureFoundationNoOps() {
        service.ensureFoundation("public");
        service.ensureFoundation("bad");
        service.ensureFoundation(null);
        verifyNoInteractions(migrations, rows);

        when(migrations.schemaExists(A)).thenReturn(false, true);
        when(migrations.schemaVersions(eq(A), anyCollection())).thenReturn(LATEST);
        service.ensureFoundation(A);
        verify(migrations, never()).schemaVersions(anyString(), anyCollection());
        service.ensureFoundation(A);
        verify(migrations).schemaVersions(eq(A), anyCollection());
    }

    // ── upgradeFoundation (boot) ──────────────────────────────────────────────

    @Test
    @DisplayName("boot: set-based detection — up-to-date tenants get no Flyway run, behind ones only their modules, a failing tenant is isolated")
    void upgradeFoundation() {
        when(rows.liveTenants()).thenReturn(List.of(
                new LiveTenant("KV-ADMIN", "public", true),        // platform: never a tenant schema
                new LiveTenant("KV-FNDA01", A, true),               // up to date
                new LiveTenant("KV-FNDB02", B, true),               // provbatch row missing
                new LiveTenant("KV-FNDC03", C, true),               // core history missing (never provisioned)
                new LiveTenant("KV-FNDD04", D, true),               // provtest behind, its migration fails
                new LiveTenant("KV-FNDE05", E, false)));            // schema not created: skipped
        when(migrations.historyVersions(eq("provbatch"), anyCollection())).thenReturn(Map.of(A, "1", C, "1", D, "1"));
        when(migrations.historyVersions(eq("provtest"), anyCollection()))
                .thenReturn(Map.of(A, "2", B, "2", C, "2", D, "1"));
        when(migrations.historyVersions(eq("core"), anyCollection())).thenReturn(Map.of(A, "1", B, "1", D, "1"));
        when(migrations.migrate(eq(D), eq("provtest")))
                .thenThrow(new ModuleProvisioningException("provtest", D, "55P03", "1"));

        FoundationUpgrade upgrade = service.upgradeFoundation(2);

        assertThat(upgrade.checked()).isEqualTo(4);
        assertThat(upgrade.provisioned()).isEqualTo(2);
        assertThat(upgrade.failed()).isEqualTo(1);
        verify(migrations, never()).migrate(eq(A), anyString());
        verify(migrations).migrate(eq(B), eq("provbatch"));
        verify(migrations, never()).migrate(eq(B), eq("provtest"));
        verify(migrations).migrate(eq(C), eq("core"));
        verify(migrations, never()).migrate(eq(C), eq("provtest"));
        verify(migrations, never()).migrate(eq(E), anyString());
        verify(migrations, never()).migrate(eq("public"), anyString());
        // one history read per foundation module (the Flyway history decides, as on the request path)
        for (String module : LATEST.keySet()) {
            verify(migrations, times(1)).historyVersions(eq(module), anyCollection());
        }
        verify(migrations, never()).schemaVersions(anyString(), anyCollection());
        verify(migrations, never()).currentVersion(anyString(), anyString());

        // up-to-date and upgraded tenants are cached; the failed one is checked again on its first request
        clearInvocations(migrations);
        service.ensureFoundation(A);
        service.ensureFoundation(B);
        service.ensureFoundation(C);
        verifyNoInteractions(migrations);
        when(migrations.schemaVersions(eq(D), anyCollection())).thenReturn(Map.of("core", "1", "provbatch", "1"));
        when(migrations.migrate(eq(D), eq("provtest"))).thenReturn(new ModuleMigrationPort.Outcome("1", "2", 1));
        service.ensureFoundation(D);
        verify(migrations).migrate(eq(D), eq("provtest"));
    }

    @Test
    @DisplayName("boot: an unreadable history version keeps the tenant behind (provisioned, failure counted, others go on)")
    void upgradeFoundationUnreadableVersion() {
        when(rows.liveTenants()).thenReturn(List.of(new LiveTenant("KV-FNDA01", A, true),
                new LiveTenant("KV-FNDB02", B, true)));
        when(migrations.historyVersions(eq("core"), anyCollection())).thenReturn(Map.of(A, "1", B, "1"));
        when(migrations.historyVersions(eq("provbatch"), anyCollection())).thenReturn(Map.of(A, "1", B, "1"));
        when(migrations.historyVersions(eq("provtest"), anyCollection()))
                .thenReturn(Map.of(A, "unreadable", B, "2"));
        when(migrations.migrate(eq(A), eq("provtest")))
                .thenThrow(new ModuleProvisioningException("provtest", A, "FAILED_VALIDATION", null));

        FoundationUpgrade upgrade = service.upgradeFoundation(2);

        assertThat(upgrade.failed()).isEqualTo(1);
        assertThat(upgrade.provisioned()).isZero();
        verify(migrations, never()).migrate(eq(B), anyString());
    }

    @Test
    @DisplayName("boot: every tenant up to date → no Flyway run at all")
    void upgradeFoundationNothingToDo() {
        when(rows.liveTenants()).thenReturn(List.of(new LiveTenant("KV-FNDA01", A, true)));
        when(migrations.historyVersions(eq("core"), anyCollection())).thenReturn(Map.of(A, "1"));
        when(migrations.historyVersions(eq("provbatch"), anyCollection())).thenReturn(Map.of(A, "1"));
        when(migrations.historyVersions(eq("provtest"), anyCollection())).thenReturn(Map.of(A, "2"));

        FoundationUpgrade upgrade = service.upgradeFoundation(4);

        assertThat(upgrade.checked()).isEqualTo(1);
        assertThat(upgrade.provisioned()).isZero();
        assertThat(upgrade.failed()).isZero();
        verify(migrations, never()).migrate(anyString(), anyString());
    }
}
