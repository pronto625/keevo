package com.keevo.migration.application;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantView;
import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.ReconciliationStatus;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefMonetisationSourcePort;
import com.keevo.migration.domain.port.out.BrefSourcePort;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.port.out.ReconciliationStore;
import com.keevo.migration.domain.port.out.ReconciliationTargetReader;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.PlanAdministrationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("24.8 — reconciliation service: only completed imports, a run that cannot read is stored failed")
class BrefReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private final BrefSourcePort source = mock(BrefSourcePort.class);
    private final BrefMonetisationSourcePort monetisation = mock(BrefMonetisationSourcePort.class);
    private final MigrationRecordStore batches = mock(MigrationRecordStore.class);
    private final ReconciliationTargetReader target = mock(ReconciliationTargetReader.class);
    private final ReconciliationStore store = mock(ReconciliationStore.class);
    private final TenantQueryPort tenants = mock(TenantQueryPort.class);
    private final BrefReconciliationService service;
    private final UUID owner = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    BrefReconciliationServiceTest() {
        when(source.kind()).thenReturn(SourceKind.DATABASE);
        when(monetisation.kind()).thenReturn(SourceKind.DATABASE);
        service = new BrefReconciliationService(List.of(source), List.of(monetisation), batches, target, store,
                tenants, mock(PlanAdministrationPort.class), mock(PlanCatalog.class), mock(MigrationUnitOfWork.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MigrationBatch batch(BatchStatus status) {
        MigrationBatch b = new MigrationBatch(UUID.randomUUID(), owner, SourceKind.DATABASE, status, tenant, actor,
                NOW, NOW, null, null, null, null);
        when(batches.findBatch(b.id())).thenReturn(Optional.of(b));
        when(store.latestBatch(owner)).thenReturn(Optional.of(b.id()));
        return b;
    }

    @Test
    @DisplayName("source unreachable → stored RECONCILIATION_FAILED with its French label, not thrown")
    void sourceUnavailable() {
        MigrationBatch b = batch(BatchStatus.COMPLETED);
        when(tenants.findById(tenant)).thenReturn(Optional.of(new TenantView(tenant, "KV-ABCDEF", "kv_abcdef",
                "Entreprise", TenantStatus.SUSPENDED, null, NOW, null)));
        when(source.available()).thenReturn(true);
        when(source.load(owner)).thenThrow(new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "down"));

        ReconciliationReport report = service.reconcile(b.id(), actor);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.RECONCILIATION_FAILED);
        assertThat(report.errorLabel()).isEqualTo(BrefReconciliationService.SOURCE_UNAVAILABLE);
        assertThat(report.checks()).isEmpty();
        ArgumentCaptor<ReconciliationReport> saved = ArgumentCaptor.forClass(ReconciliationReport.class);
        verify(store).save(saved.capture(), eq(actor));
        assertThat(saved.getValue().batchId()).isEqualTo(b.id());
    }

    @Test
    @DisplayName("source without the owner or unreadable → its own label; a superseded batch → INVALID_STATE")
    void sourceChangedAndSuperseded() {
        MigrationBatch b = batch(BatchStatus.COMPLETED);
        when(tenants.findById(tenant)).thenReturn(Optional.of(new TenantView(tenant, "KV-ABCDEF", "kv_abcdef",
                "Entreprise", TenantStatus.SUSPENDED, null, NOW, null)));
        when(source.available()).thenReturn(true);
        when(source.load(owner)).thenThrow(new DomainException(ErrorCode.MIGRATION_OWNER_NOT_FOUND, "gone"));
        assertThat(service.reconcile(b.id(), actor).errorLabel()).isEqualTo(BrefReconciliationService.SOURCE_CHANGED);

        when(store.latestBatch(owner)).thenReturn(Optional.of(UUID.randomUUID())); // a newer import exists
        assertThatThrownBy(() -> service.reconcile(b.id(), actor)).isInstanceOf(DomainException.class)
                .hasMessageContaining("plus récent");
    }

    @Test
    @DisplayName("any other failure → stored failed with the generic label")
    void otherFailure() {
        MigrationBatch b = batch(BatchStatus.COMPLETED);
        when(tenants.findById(tenant)).thenReturn(Optional.empty());

        assertThat(service.reconcile(b.id(), actor).errorLabel()).isEqualTo(BrefReconciliationService.NOT_RUN);
    }

    @Test
    @DisplayName("BLOCKED / FAILED batch → INVALID_STATE, nothing stored; unknown batch → MIGRATION_BATCH_NOT_FOUND")
    void notCompleted() {
        MigrationBatch blocked = batch(BatchStatus.BLOCKED);
        assertThatThrownBy(() -> service.reconcile(blocked.id(), actor)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        UUID unknown = UUID.randomUUID();
        when(batches.findBatch(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcile(unknown, actor)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.MIGRATION_BATCH_NOT_FOUND);
        assertThatThrownBy(() -> service.report(blocked.id())).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.MIGRATION_RECONCILIATION_NOT_FOUND);
        verify(store, never()).save(any(), any());
    }
}
