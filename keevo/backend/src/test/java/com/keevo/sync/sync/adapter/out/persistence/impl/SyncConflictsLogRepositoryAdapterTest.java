package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncConflictsLogJpaEntity;
import com.keevo.sync.sync.adapter.out.persistence.jpa.SyncConflictsLogSpringRepository;
import com.keevo.sync.sync.domain.model.SyncConflictsLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncConflictsLogRepositoryAdapterTest {

    @Mock
    private SyncConflictsLogSpringRepository jpa;

    private SyncConflictsLogRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SyncConflictsLogRepositoryAdapter(jpa);
    }

    @Test
    void save_validEntry_persistsViaJpa() {
        var entry = new SyncConflictsLogEntry(
                "conflict-1", "op-1", "STOCK_UPDATE", "entity-1", null,
                "STOCK_NEGATIVE", "DELTA_SUM",
                Map.of("serverQty", 5, "requestedDelta", -10),
                Instant.now(), "actor-1");

        adapter.save(entry);

        ArgumentCaptor<SyncConflictsLogJpaEntity> captor = ArgumentCaptor.forClass(SyncConflictsLogJpaEntity.class);
        verify(jpa).save(captor.capture());
        SyncConflictsLogJpaEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("conflict-1");
        assertThat(saved.getOperationId()).isEqualTo("op-1");
        assertThat(saved.getConflictType()).isEqualTo("STOCK_NEGATIVE");
        assertThat(saved.getStrategy()).isEqualTo("DELTA_SUM");
        assertThat(saved.getConflictData()).containsEntry("serverQty", 5);
    }

    @Test
    void findAll_delegatesToJpaWithPagination() {
        var jpaEntity = new SyncConflictsLogJpaEntity(
                "c-1", "op-1", "STOCK_UPDATE", "e-1", null,
                "LAST_WRITE_WINS", "LWW", Map.of(), Instant.now(), null);
        when(jpa.findAllByOrderByResolvedAtDesc(any(PageRequest.class)))
                .thenReturn(List.of(jpaEntity));

        List<SyncConflictsLogEntry> result = adapter.findAll(50, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("c-1");
        assertThat(result.get(0).conflictType()).isEqualTo("LAST_WRITE_WINS");
    }

    @Test
    void findByEntityId_returnsMappedEntries() {
        var jpaEntity = new SyncConflictsLogJpaEntity(
                "c-2", "op-2", "STOCK_UPDATE", "entity-42", null,
                "STOCK_NEGATIVE", "DELTA_SUM", Map.of("serverQty", 0), Instant.now(), "actor-1");
        when(jpa.findByEntityId("entity-42")).thenReturn(List.of(jpaEntity));

        List<SyncConflictsLogEntry> result = adapter.findByEntityId("entity-42");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entityId()).isEqualTo("entity-42");
        assertThat(result.get(0).strategy()).isEqualTo("DELTA_SUM");
    }

    @Test
    void save_conflictDataAsMap_preservesAllFields() {
        Map<String, Object> conflictData = Map.of(
                "serverQty", 10,
                "requestedDelta", -15,
                "resultingQty", -5,
                "productName", "Widget A");
        var entry = new SyncConflictsLogEntry(
                "c-3", "op-3", "STOCK_UPDATE", "e-3", null,
                "STOCK_NEGATIVE", "DELTA_SUM", conflictData,
                Instant.now(), "actor-2");

        adapter.save(entry);

        ArgumentCaptor<SyncConflictsLogJpaEntity> captor = ArgumentCaptor.forClass(SyncConflictsLogJpaEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getConflictData()).hasSize(4);
        assertThat(captor.getValue().getConflictData()).containsEntry("productName", "Widget A");
    }
}
