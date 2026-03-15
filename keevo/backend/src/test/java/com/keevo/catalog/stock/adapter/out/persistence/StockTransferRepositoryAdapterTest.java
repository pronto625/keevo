package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StockTransferRepositoryAdapterTest — TDD RED phase first.
 * Task 8.3 — Story 3.3.
 */
@ExtendWith(MockitoExtension.class)
class StockTransferRepositoryAdapterTest {

    @Mock private StockTransferSpringRepository springRepo;

    private StockTransferRepositoryAdapter adapter;

    private static final UUID SRC_ID   = UUID.randomUUID();
    private static final UUID DEST_ID  = UUID.randomUUID();
    private static final UUID PROD_ID  = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new StockTransferRepositoryAdapter(springRepo);
    }

    private com.keevo.shared.infrastructure.persistence.entity.StockTransferJpaEntity buildEntity() {
        return new com.keevo.shared.infrastructure.persistence.entity.StockTransferJpaEntity(
            UUID.randomUUID(), SRC_ID, DEST_ID, PROD_ID, null, 5, ACTOR_ID,
            Instant.now(), TransferStatus.COMPLETED, null
        );
    }

    @Test
    void save_shouldPersistTransfer_withCorrectFields() {
        var transfer = new StockTransfer(UUID.randomUUID(), SRC_ID, DEST_ID, PROD_ID, null, 5,
            ACTOR_ID, Instant.now(), TransferStatus.COMPLETED, "notes");
        var entity = buildEntity();
        when(springRepo.save(any())).thenReturn(entity);

        var result = adapter.save(transfer);

        assertThat(result).isNotNull();
        assertThat(result.getSourceStoreId()).isEqualTo(SRC_ID);
        verify(springRepo).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByFilters_shouldFilterBySourceStore() {
        var pageable = PageRequest.of(0, 25);
        when(springRepo.findAll(any(Specification.class), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of()));

        adapter.findByFilters(SRC_ID, null, null, null, pageable);

        verify(springRepo).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByFilters_shouldFilterByDestinationStore() {
        var pageable = PageRequest.of(0, 25);
        when(springRepo.findAll(any(Specification.class), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of()));

        adapter.findByFilters(null, DEST_ID, null, null, pageable);

        verify(springRepo).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByFilters_shouldFilterByDateRange() {
        var from = Instant.now().minusSeconds(3600);
        var to   = Instant.now();
        var pageable = PageRequest.of(0, 25);
        when(springRepo.findAll(any(Specification.class), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of()));

        adapter.findByFilters(null, null, from, to, pageable);

        verify(springRepo).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByFilters_shouldOrderByOccurredAtDesc() {
        var pageable = PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "occurredAt"));
        when(springRepo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        adapter.findByFilters(null, null, null, null, pageable);

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(springRepo).findAll(any(Specification.class), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getSort().getOrderFor("occurredAt"))
            .isNotNull();
    }
}
