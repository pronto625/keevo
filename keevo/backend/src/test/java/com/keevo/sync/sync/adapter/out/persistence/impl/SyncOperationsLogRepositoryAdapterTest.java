package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncOperationsLogJpaEntity;
import com.keevo.sync.sync.adapter.out.persistence.jpa.SyncOperationsLogSpringRepository;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncOperationsLogRepositoryAdapterTest {

    @Mock private SyncOperationsLogSpringRepository jpa;

    private SyncOperationsLogRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SyncOperationsLogRepositoryAdapter(jpa);
    }

    @Test
    void existsById_whenExists_returnsTrue() {
        when(jpa.existsById("op-123")).thenReturn(true);
        assertThat(adapter.existsById("op-123")).isTrue();
    }

    @Test
    void existsById_whenNotExists_returnsFalse() {
        when(jpa.existsById("op-999")).thenReturn(false);
        assertThat(adapter.existsById("op-999")).isFalse();
    }

    private static SyncOperationsLogJpaEntity logged(String id, SyncOperationStatus status) {
        return new SyncOperationsLogJpaEntity(
                id, "CREATE_SALE", "e1", status.name(), null, Instant.now(), Instant.now());
    }

    @Test
    void isSettled_whenApplied_returnsTrue() {
        when(jpa.findById("op-1")).thenReturn(java.util.Optional.of(logged("op-1", SyncOperationStatus.APPLIED)));
        assertThat(adapter.isSettled("op-1")).isTrue();
    }

    @Test
    void isSettled_whenRejected_returnsFalse() {
        when(jpa.findById("op-2")).thenReturn(java.util.Optional.of(logged("op-2", SyncOperationStatus.REJECTED)));
        assertThat(adapter.isSettled("op-2")).isFalse();
    }

    @Test
    void isSettled_whenNeverLogged_returnsFalse() {
        when(jpa.findById("op-3")).thenReturn(java.util.Optional.empty());
        assertThat(adapter.isSettled("op-3")).isFalse();
    }

    @Test
    void save_persistsAllFields() {
        var entry = new SyncOperationsLogEntry(
                "op-1", "CREATE_SALE", "entity-1",
                SyncOperationStatus.APPLIED, null,
                Instant.parse("2026-03-20T12:00:00Z"),
                Instant.parse("2026-03-20T11:59:00Z"));

        adapter.save(entry);

        ArgumentCaptor<SyncOperationsLogJpaEntity> captor =
                ArgumentCaptor.forClass(SyncOperationsLogJpaEntity.class);
        verify(jpa).save(captor.capture());

        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("op-1");
        assertThat(saved.getOperationType()).isEqualTo("CREATE_SALE");
        assertThat(saved.getEntityId()).isEqualTo("entity-1");
        assertThat(saved.getStatus()).isEqualTo("APPLIED");
        assertThat(saved.getErrorReason()).isNull();
        assertThat(saved.getProcessedAt()).isEqualTo(Instant.parse("2026-03-20T12:00:00Z"));
        assertThat(saved.getClientTimestamp()).isEqualTo(Instant.parse("2026-03-20T11:59:00Z"));
    }

    @Test
    void save_rejectedWithReason_persistsErrorReason() {
        var entry = new SyncOperationsLogEntry(
                "op-2", "UNKNOWN_TYPE", null,
                SyncOperationStatus.REJECTED, "UNKNOWN_OPERATION_TYPE",
                Instant.now(), null);

        adapter.save(entry);

        ArgumentCaptor<SyncOperationsLogJpaEntity> captor =
                ArgumentCaptor.forClass(SyncOperationsLogJpaEntity.class);
        verify(jpa).save(captor.capture());

        assertThat(captor.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(captor.getValue().getErrorReason()).isEqualTo("UNKNOWN_OPERATION_TYPE");
    }

    @Test
    void isSettledElseClaim_whenRowInserted_returnsFalse() {
        when(jpa.claim(eq("op-1"), eq("CREATE_SALE"), eq("e1"), any(), any())).thenReturn(1);
        assertThat(adapter.isSettledElseClaim("op-1", "CREATE_SALE", "e1", Instant.now())).isFalse();
    }

    @Test
    void isSettledElseClaim_whenRowAlreadySettled_returnsTrue() {
        when(jpa.claim(eq("op-1"), eq("CREATE_SALE"), eq("e1"), any(), any())).thenReturn(0);
        assertThat(adapter.isSettledElseClaim("op-1", "CREATE_SALE", "e1", Instant.now())).isTrue();
    }

    @Test
    void isSettledElseClaim_nullClientTimestamp_bindsANonNullValue() {
        when(jpa.claim(any(), any(), any(), any(), any())).thenReturn(1);
        adapter.isSettledElseClaim("op-1", "CREATE_SALE", "e1", null);
        verify(jpa).claim(eq("op-1"), eq("CREATE_SALE"), eq("e1"), notNull(), notNull());
    }
}
