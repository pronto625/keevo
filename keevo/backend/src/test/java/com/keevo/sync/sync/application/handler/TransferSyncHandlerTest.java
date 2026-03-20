package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.port.in.TransferStockUseCase;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferSyncHandlerTest {

    @Mock private TransferStockUseCase transferStockUseCase;

    private TransferSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new TransferSyncHandler(transferStockUseCase);
    }

    @Test
    void handle_validTransfer_delegatesToExecuteTransferService() {
        var transferId = UUID.randomUUID();
        var mockTransfer = mock(StockTransfer.class);
        when(mockTransfer.getId()).thenReturn(transferId);
        when(transferStockUseCase.execute(any())).thenReturn(mockTransfer);

        var op = new SyncOperation("op-1", "STOCK_TRANSFER", UUID.randomUUID().toString(),
                Map.of("sourceStoreId", UUID.randomUUID().toString(),
                        "destinationStoreId", UUID.randomUUID().toString(),
                        "productId", UUID.randomUUID().toString(),
                        "quantity", 10),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(transferStockUseCase).execute(any());
    }

    @Test
    void handle_invalidTransfer_returnsRejected() {
        var op = new SyncOperation("op-1", "STOCK_TRANSFER", UUID.randomUUID().toString(),
                Map.of("sourceStoreId", UUID.randomUUID().toString(),
                        "destinationStoreId", UUID.randomUUID().toString(),
                        "productId", UUID.randomUUID().toString(),
                        "quantity", 10),
                Instant.now());

        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK))
                .when(transferStockUseCase).execute(any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void supportedTypes_containsStockTransfer() {
        assertThat(handler.supportedTypes()).containsExactly("STOCK_TRANSFER");
    }
}
