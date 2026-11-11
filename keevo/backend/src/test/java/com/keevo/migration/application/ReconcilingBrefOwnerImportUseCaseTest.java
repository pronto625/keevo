package com.keevo.migration.application;

import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.in.BrefReconciliationUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("24.8 — every completed import is reconciled right after")
class ReconcilingBrefOwnerImportUseCaseTest {

    private final BrefOwnerImportService imports = mock(BrefOwnerImportService.class);
    private final BrefReconciliationUseCase reconciliation = mock(BrefReconciliationUseCase.class);
    private final ReconcilingBrefOwnerImportUseCase useCase = new ReconcilingBrefOwnerImportUseCase(imports,
            reconciliation);
    private final UUID owner = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private MigrationBatch batch(BatchStatus status) {
        return new MigrationBatch(UUID.randomUUID(), owner, SourceKind.DATABASE, status, UUID.randomUUID(), actor,
                Instant.now(), Instant.now(), null, null, null, null);
    }

    @Test
    @DisplayName("COMPLETED → reconciled; the import's batch is returned as is")
    void completed() {
        MigrationBatch b = batch(BatchStatus.COMPLETED);
        when(imports.importOwner(owner, "DATABASE", actor)).thenReturn(b);

        assertThat(useCase.importOwner(owner, "DATABASE", actor)).isSameAs(b);
        verify(reconciliation).reconcile(b.id(), actor);
    }

    @Test
    @DisplayName("BLOCKED or FAILED → not reconciled (the gate blocks the owner anyway)")
    void notCompleted() {
        when(imports.importOwner(owner, "EXPORT", actor)).thenReturn(batch(BatchStatus.BLOCKED),
                batch(BatchStatus.FAILED));

        useCase.importOwner(owner, "EXPORT", actor);
        useCase.importOwner(owner, "EXPORT", actor);
        verify(reconciliation, never()).reconcile(any(), any());
    }

    @Test
    @DisplayName("a reconciliation that throws never hides the import's outcome")
    void reconciliationThrows() {
        MigrationBatch b = batch(BatchStatus.COMPLETED);
        when(imports.importOwner(owner, "DATABASE", actor)).thenReturn(b);
        when(reconciliation.reconcile(b.id(), actor)).thenThrow(new IllegalStateException("store down"));

        assertThat(useCase.importOwner(owner, "DATABASE", actor)).isSameAs(b);
        when(imports.batch(b.id())).thenReturn(b);
        assertThat(useCase.batch(b.id())).isSameAs(b);
    }
}
