package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.shared.infrastructure.persistence.entity.StockMovementJpaEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StockMovementRepositoryAdapterTest — characterizes domain↔JPA mapping + Specification/Pageable delegation.
 * Story 15.3 — Task 6.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockMovementRepositoryAdapter")
class StockMovementRepositoryAdapterTest {

    @Mock
    private StockMovementSpringRepository springRepository;

    private StockMovementRepositoryAdapter adapter;

    private static final UUID MOVEMENT_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        adapter = new StockMovementRepositoryAdapter(springRepository);
    }

    // ─────────────────────────────────────────────────────────────────
    // 6.2 — save: domain → JPA entity → domain roundtrip
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save maps domain to JPA entity and back preserving all fields")
    void save_mapsDomainToJpaEntityAndBack_preservesAllFields() {
        StockMovement movement = new StockMovement(MOVEMENT_ID, PRODUCT_ID, null, STORE_ID,
                MovementType.SALE, 10, -3, 7, ACTOR_ID, "Sold 3 units", NOW);
        StockMovementJpaEntity savedEntity = new StockMovementJpaEntity(
                MOVEMENT_ID, PRODUCT_ID, null, STORE_ID, MovementType.SALE, 10, -3, 7, ACTOR_ID, "Sold 3 units", NOW);
        when(springRepository.save(any(StockMovementJpaEntity.class))).thenReturn(savedEntity);

        StockMovement result = adapter.save(movement);

        assertThat(result.getId()).isEqualTo(MOVEMENT_ID);
        assertThat(result.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(result.getStoreId()).isEqualTo(STORE_ID);
        assertThat(result.getMovementType()).isEqualTo(MovementType.SALE);
        assertThat(result.getQuantityBefore()).isEqualTo(10);
        assertThat(result.getQuantityChange()).isEqualTo(-3);
        assertThat(result.getQuantityAfter()).isEqualTo(7);
        assertThat(result.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(result.getNotes()).isEqualTo("Sold 3 units");
        assertThat(result.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("save when movement id is null generates new UUID")
    void save_whenMovementIdNull_generatesNewUuid() {
        StockMovement movement = new StockMovement(null, PRODUCT_ID, null, STORE_ID,
                MovementType.SALE, 10, -3, 7, ACTOR_ID, "Notes", NOW);
        when(springRepository.save(any(StockMovementJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        StockMovement result = adapter.save(movement);

        assertNotNull(result.getId(), "A new UUID should be generated when id is null");
        verify(springRepository).save(any(StockMovementJpaEntity.class));
    }

    @Test
    @DisplayName("save when occurredAt is null at construction is defaulted to close to now by the domain constructor, and passes through the adapter unchanged")
    void save_whenOccurredAtNull_defaultsToNow() {
        // Note: StockMovement's own constructor already defaults a null occurredAt to Instant.now()
        // (domain/entity/StockMovement.java) before this object ever reaches the adapter — so
        // movement.getOccurredAt() is never null by the time toJpaEntity() runs. The adapter's own
        // `m.getOccurredAt() != null ? ... : Instant.now()` fallback is therefore unreachable/dead
        // code via any current public API. This test verifies the adapter transparently passes
        // through the domain-defaulted value; it does not (and cannot) exercise the adapter's own
        // fallback branch.
        Instant before = Instant.now();
        StockMovement movement = new StockMovement(MOVEMENT_ID, PRODUCT_ID, null, STORE_ID,
                MovementType.SALE, 10, -3, 7, ACTOR_ID, null, null);
        when(springRepository.save(any(StockMovementJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        StockMovement result = adapter.save(movement);

        assertNotNull(result.getOccurredAt(), "occurredAt should be set to now when null");
        Instant after = Instant.now();
        // Within a few seconds tolerance
        assertThat(result.getOccurredAt()).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    // ─────────────────────────────────────────────────────────────────
    // 6.4 — findByProductId: Specification + Pageable delegation (all filters null)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("findByProductId delegates to Specification findAll with all optional filters null")
    void findByProductId_delegatesToSpecificationFindAll_withAllFiltersNull() {
        Pageable pageable = PageRequest.of(0, 25);
        Page<StockMovementJpaEntity> page = new PageImpl<>(List.of());
        when(springRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<StockMovement> result = adapter.findByProductId(PRODUCT_ID, null, null, null, null, pageable);

        assertThat(result).isEmpty();
        verify(springRepository).findAll(any(Specification.class), eq(pageable));
    }

    // ─────────────────────────────────────────────────────────────────
    // 6.5 — findByProductId: all filters provided
    // ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("findByProductId delegates to Specification findAll with all filters provided")
    void findByProductId_delegatesToSpecificationFindAll_withAllFiltersProvided() {
        Instant from = NOW.minusSeconds(3600);
        Instant to = NOW;
        Pageable pageable = PageRequest.of(0, 10);
        Page<StockMovementJpaEntity> page = new PageImpl<>(List.of());
        when(springRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        Page<StockMovement> result = adapter.findByProductId(PRODUCT_ID, MovementType.SALE,
                from, to, STORE_ID, pageable);

        assertThat(result).isEmpty();
        verify(springRepository).findAll(any(Specification.class), eq(pageable));
    }
}
