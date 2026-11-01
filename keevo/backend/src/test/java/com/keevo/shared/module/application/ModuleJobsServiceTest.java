package com.keevo.shared.module.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.job.AdminJobs;
import com.keevo.shared.job.application.AdminJobRunner;
import com.keevo.shared.job.application.AdminJobRunnerTest;
import com.keevo.shared.module.ModuleBackfill;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ProvisioningReport;
import com.keevo.shared.module.TestModuleCatalogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 16.4 — {@link ModuleJobsService} on the real job runner (in-memory jobs, manual executor). */
@DisplayName("ModuleJobsService (Story 16.4)")
class ModuleJobsServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();

    private final AdminJobRunnerTest.InMemoryStore store = new AdminJobRunnerTest.InMemoryStore();
    private final List<Runnable> queued = new ArrayList<>();
    private final ModuleBackfill backfill = mock(ModuleBackfill.class);
    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);
    private ModuleJobsService service;
    private AdminJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AdminJobRunner(store, new ObjectMapper(), queued::add);
        service = new ModuleJobsService(runner, backfill, provisioner, TestModuleCatalogs.lifecycleCatalog());
        when(provisioner.hasMigrations("lcpaid")).thenReturn(true);
    }

    private void runQueued() {
        List<Runnable> now = new ArrayList<>(queued);
        queued.clear();
        now.forEach(Runnable::run);
    }

    @Test
    @DisplayName("unknown or platform module → 404 MODULE_NOT_FOUND, no job")
    void notFound() {
        for (String id : new String[] {"nope", "lcadmin", null}) {
            DomainException e = catchThrowableOfType(DomainException.class,
                    () -> service.startProvisionAll(id, false, ACTOR));
            assertThat(e.getDomainCode()).isEqualTo("MODULE_NOT_FOUND");
        }
        assertThat(store.jobs).isEmpty();
    }

    @Test
    @DisplayName("a module without tables → 422 naming it in French, no job row")
    void noMigrations() {
        DomainException e = catchThrowableOfType(DomainException.class,
                () -> service.startProvisionAll("lcplain", false, ACTOR));
        assertThat(e.getDomainCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(e.getDetails()).containsEntry("moduleId", "Le module « Module simple » n'a pas de tables à créer");
        assertThat(store.jobs).isEmpty();
        verify(provisioner, never()).provisionAll(anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("a module with tables → MODULE_PROVISION_ALL job queued with its id and name; the work provisions it")
    void provisionQueued() {
        when(provisioner.provisionAll(eq("lcpaid"), eq(true), any()))
                .thenReturn(new ProvisioningReport("lcpaid", List.of("KV-AAA111"), List.of(), List.of(), 0));

        UUID id = service.startProvisionAll("lcpaid", true, ACTOR);

        AdminJobs.JobView job = runner.find(id).orElseThrow();
        assertThat(job.kind()).isEqualTo(AdminJobs.Kind.MODULE_PROVISION_ALL);
        assertThat(job.params()).containsEntry("moduleId", "lcpaid").containsEntry("module", "Module payant")
                .containsEntry("force", true);
        runQueued();
        assertThat(runner.find(id).orElseThrow().status()).isEqualTo(AdminJobs.Status.SUCCEEDED);
        verify(provisioner).provisionAll(eq("lcpaid"), eq(true), any());
    }

    @Test
    @DisplayName("the backfill lock held elsewhere → the job ends FAILED with the French « déjà en cours » reason")
    void backfillAlreadyRunning() {
        when(backfill.run(eq(true), any())).thenThrow(new DomainException(ErrorCode.MODULE_BACKFILL_RUNNING,
                "A module backfill is already running"));

        UUID id = service.startBackfill(true, ACTOR);
        runQueued();

        AdminJobs.JobView job = runner.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(AdminJobs.Status.FAILED);
        assertThat(job.error()).isEqualTo("Une migration des modules est déjà en cours. Réessayez dans quelques minutes.");
    }
}
