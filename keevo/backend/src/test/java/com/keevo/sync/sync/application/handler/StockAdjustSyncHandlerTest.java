package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.stock.application.usecase.AdjustStockUseCase;
import com.keevo.catalog.stock.application.usecase.RecordStockEntryUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StockAdjustSyncHandlerTest {

    @Mock private AdjustStockUseCase adjustStockUseCase;
    @Mock private RecordStockEntryUseCase recordStockEntryUseCase;

    private StockAdjustSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new StockAdjustSyncHandler(adjustStockUseCase, recordStockEntryUseCase);
    }

    @Test
    void handle_validAdjust_delegatesToStockOperationService() {
        var op = new SyncOperation("op-1", "STOCK_ADJUST", UUID.randomUUID().toString(),
                Map.of("productId", UUID.randomUUID().toString(),
                        "storeId", UUID.randomUUID().toString(),
                        "quantity", 50),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
    }

    @Test
    void handle_negativeStock_returnsConflict() {
        var op = new SyncOperation("op-1", "STOCK_ADJUST", UUID.randomUUID().toString(),
                Map.of("productId", UUID.randomUUID().toString(),
                        "storeId", UUID.randomUUID().toString(),
                        "quantity", -5),
                Instant.now());

        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK))
                .when(adjustStockUseCase).execute(any(), any(), any(), anyInt(), any(), any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
    }

    @Test
    void handle_recordStockEntry_delegates() {
        var op = new SyncOperation("op-1", "RECORD_STOCK_ENTRY", UUID.randomUUID().toString(),
                Map.of("productId", UUID.randomUUID().toString(),
                        "storeId", UUID.randomUUID().toString(),
                        "quantity", 20),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
    }

    @Test
    void supportedTypes_containsStockAdjustAndEntry() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("STOCK_ADJUST", "RECORD_STOCK_ENTRY");
    }
}
