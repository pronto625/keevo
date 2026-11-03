package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.module.FoundationUpgrade;
import com.keevo.shared.module.ModuleProvisioner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 16.12 — at boot the runner asks the module provisioner to upgrade the foundation tables of the tenants that
 * are behind (bounded parallelism), logs one summary line, and never aborts startup.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TenantSchemaMigrationRunner — boot-time foundation upgrade")
class TenantSchemaMigrationRunnerTest {

    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);

    @Test
    @DisplayName("summary line: tenants checked, provisioned, failed, duration; the configured parallelism is used")
    void summary(CapturedOutput output) {
        when(provisioner.upgradeFoundation(3)).thenReturn(new FoundationUpgrade(344, 12, 0, 1500));

        FoundationUpgrade upgrade = new TenantSchemaMigrationRunner(provisioner, 3).runAll();

        assertThat(upgrade.provisioned()).isEqualTo(12);
        assertThat(output.getOut()).contains("Tenant tables: 344 tenant(s) checked, 12 provisioned, 0 failed, 1500 ms");
    }

    @Test
    @DisplayName("failed tenants are reported at ERROR, startup goes on")
    void failures(CapturedOutput output) {
        when(provisioner.upgradeFoundation(4)).thenReturn(new FoundationUpgrade(10, 8, 2, 300));

        new TenantSchemaMigrationRunner(provisioner, 4).runAll();

        assertThat(output.getOut() + output.getErr()).contains("ERROR").contains("2 FAILED");
    }

    @Test
    @DisplayName("an unexpected failure of the upgrade never throws (tenants are provisioned on first request)")
    void neverThrows(CapturedOutput output) {
        when(provisioner.upgradeFoundation(4)).thenThrow(new IllegalStateException("database down"));

        FoundationUpgrade upgrade = new TenantSchemaMigrationRunner(provisioner, 4).runAll();

        assertThat(upgrade.checked()).isZero();
        assertThat(output.getOut() + output.getErr()).contains("the boot upgrade could not run");
    }

    @Test
    @DisplayName("parallelism below 1 falls back to 1")
    void parallelismFloor() {
        when(provisioner.upgradeFoundation(1)).thenReturn(new FoundationUpgrade(0, 0, 0, 0));

        new TenantSchemaMigrationRunner(provisioner, 0).runAll();

        verify(provisioner).upgradeFoundation(1);
    }

    @Test
    @DisplayName("SmartLifecycle: starts once, low phase (before web server and scheduler registration)")
    void lifecycle() {
        when(provisioner.upgradeFoundation(4)).thenReturn(new FoundationUpgrade(0, 0, 0, 0));
        TenantSchemaMigrationRunner runner = new TenantSchemaMigrationRunner(provisioner, 4);

        assertThat(runner.getPhase()).isEqualTo(0).isLessThan(SmartLifecycle.DEFAULT_PHASE - 1024);
        assertThat(runner.isAutoStartup()).isTrue();
        runner.start();
        runner.start();
        assertThat(runner.isRunning()).isTrue();
        verify(provisioner, times(1)).upgradeFoundation(4);
        runner.stop();
        assertThat(runner.isRunning()).isFalse();
    }
}
