package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AdjustStockUseCaseTest — Story 2.3.
 */
@ExtendWith(MockitoExtension.class)
class AdjustStockUseCaseTest {

    @Mock private StockOperationService stockOperationService;
    @Mock private StockLevelRepository  stockLevelRepository;
    @InjectMocks private AdjustStockUseCase useCase;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();
    private static final UUID ACTOR_ID   = UUID.randomUUID();

    private StockMovement fakeMovement(int before, int change) {
        return new StockMovement(UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.ADJUSTMENT, before, change, before + change, ACTOR_ID, null, Instant.now());
    }

    private StockLevel levelAt(int qty) {
        return new StockLevel(UUID.randomUUID(), PRODUCT_ID, null, STORE_ID, qty, Instant.now());
    }

    @Test
    void should_compute_delta_from_existing_level() {
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelAt(8)));
        when(stockOperationService.recordOperation(any(), any(), any(),
            eq(MovementType.ADJUSTMENT), eq(2), any(), any()))
            .thenReturn(fakeMovement(8, 2));

        // newQuantity=10, currentQuantity=8 → delta=+2
        useCase.execute(PRODUCT_ID, null, STORE_ID, 10, ACTOR_ID, "inventory count");

        verify(stockOperationService).recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.ADJUSTMENT, 2, ACTOR_ID, "inventory count");
    }

    @Test
    void should_default_to_zero_when_no_existing_level() {
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.empty());
        when(stockOperationService.recordOperation(any(), any(), any(),
            eq(MovementType.ADJUSTMENT), eq(15), any(), any()))
            .thenReturn(fakeMovement(0, 15));

        useCase.execute(PRODUCT_ID, null, STORE_ID, 15, ACTOR_ID, "initial setup");

        verify(stockOperationService).recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.ADJUSTMENT, 15, ACTOR_ID, "initial setup");
    }

    @Test
    void should_reject_negative_new_quantity() {
        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID, null, STORE_ID, -1, ACTOR_ID, "error"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("négative");
    }
}
