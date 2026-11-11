package com.keevo.migration.application.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ProvisioningResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("24.6 — FinalizeStep (module provisioning, TENANT_IMPORTED once)")
class FinalizeStepTest {

    private static final Instant T = Instant.parse("2026-10-01T08:00:00Z");
    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final MigrationRecordStore records = mock(MigrationRecordStore.class);
    private final MigrationUnitOfWork uow = mock(MigrationUnitOfWork.class);
    private final FinalizeStep step = new FinalizeStep(provisioner, audit, records, uow, new ObjectMapper());
    private final UUID owner = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private ImportRun run() {
        when(uow.tenant(anyString(), any())).thenAnswer(i -> ((Supplier<Object>) i.getArgument(1)).get());
        BrefAccount a = new BrefAccount(owner, "owner", null, false, "+237600000001", true, null, null, "A", "B", null,
                true, false, "email", null, T, T, null);
        ImportRun run = new ImportRun(new BrefSnapshot(a, List.of(), List.of(), SourceKind.DATABASE),
                UUID.randomUUID(), UUID.randomUUID(), new HashMap<>());
        run.tenant(tenant, "kv_test246");
        run.count("whatsappMessagesImported", 4);
        return run;
    }

    @Test
    @DisplayName("provisions the active modules every run, audits once ({source: bref, counts}) with its record")
    void provisionsAndAuditsOnce() {
        when(provisioner.provisionActiveModules("kv_test246"))
                .thenReturn(List.of(new ProvisioningResult("ai-assistant", null, "1", 1),
                        new ProvisioningResult("crm", "4", "4", 0)));
        ImportRun run = run();
        step.run(run);
        verify(provisioner).provisionActiveModules("kv_test246");
        assertThat(run.counts().get(FinalizeStep.MODULES_PROVISIONED)).isEqualTo(1);
        ArgumentCaptor<AuditPort.AuditEntry> entry = ArgumentCaptor.forClass(AuditPort.AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("TENANT_IMPORTED");
        assertThat(entry.getValue().entityId()).isEqualTo(tenant);
        assertThat(entry.getValue().source()).isEqualTo("SYSTEM");
        assertThat(entry.getValue().metadataJson()).contains("\"source\":\"bref\"")
                .contains("\"whatsappMessagesImported\":4");
        verify(records).insert(any(), eq(owner), anyList());
        assertThat(run.imported("user", owner, "tenant_imported")).isTrue();

        step.run(run); // recorded: provisioning again, no second entry
        verify(provisioner, times(2)).provisionActiveModules("kv_test246");
        verify(audit, times(1)).record(any(AuditPort.AuditEntry.class));
    }

    @Test
    @DisplayName("an entry committed by a run whose record was lost is not written twice")
    void auditAlreadyThere() {
        when(provisioner.provisionActiveModules(anyString())).thenReturn(List.of());
        when(audit.count(any())).thenReturn(1L);
        ImportRun run = run();
        step.run(run);
        verify(audit, never()).record(any(AuditPort.AuditEntry.class));
        verify(records).insert(any(), eq(owner), anyList());
    }
}
