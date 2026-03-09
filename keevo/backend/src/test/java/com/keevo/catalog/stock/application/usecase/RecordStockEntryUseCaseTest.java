package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RecordStockEntryUseCaseTest — Story 2.3.
 */
@ExtendWith(MockitoExtension.class)
class RecordStockEntryUseCaseTest {

    @Mock private StockOperationService stockOperationService;
    @InjectMocks private RecordStockEntryUseCase useCase;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();
    private static final UUID ACTOR_ID   = UUID.randomUUID();

    private StockMovement fakeMovement(int qty) {
        return new StockMovement(UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.STOCK_ENTRY, 0, qty, qty, ACTOR_ID, null, Instant.now());
    }

    @Test
    void should_delegate_to_service_with_stock_entry_type() {
        when(stockOperationService.recordOperation(any(), any(), any(), eq(MovementType.STOCK_ENTRY),
            eq(10), any(), any())).thenReturn(fakeMovement(10));

        StockMovement result = useCase.execute(PRODUCT_ID, null, STORE_ID, 10, ACTOR_ID, "delivery");

        assertThat(result.getMovementType()).isEqualTo(MovementType.STOCK_ENTRY);
        assertThat(result.getQuantityChange()).isEqualTo(10);
        verify(stockOperationService).recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 10, ACTOR_ID, "delivery");
    }

    @Test
    void should_reject_zero_or_negative_quantity() {
        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID, null, STORE_ID, 0, ACTOR_ID, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID, null, STORE_ID, -5, ACTOR_ID, null))
            .isInstanceOf(IllegalArgumentException.class);

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
    }
}
