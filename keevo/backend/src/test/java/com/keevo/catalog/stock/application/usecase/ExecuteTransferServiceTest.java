package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.event.StockTransferredEvent;
import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.port.out.StockTransferRepository;
import com.keevo.catalog.stock.domain.service.DefaultTransferValidationStrategy;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.catalog.stock.domain.service.TransferValidationStrategy;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExecuteTransferServiceTest — TDD RED phase first.
 * Task 6.1 — Story 3.3.
 */
@ExtendWith(MockitoExtension.class)
class ExecuteTransferServiceTest {

    @Mock private StoreRepository storeRepository;
    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private StockOperationService stockOperationService;
    @Mock private StockTransferRepository transferRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private TransferValidationStrategy validationStrategy;
    private ExecuteTransferService service;

    private static final UUID SRC_STORE_ID  = UUID.randomUUID();
    private static final UUID DEST_STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID    = UUID.randomUUID();
    private static final UUID ACTOR_ID      = UUID.randomUUID();

    private Store activeStore(UUID id) {
        return new Store(id, "Store " + id, StoreType.STORE, null, null, true, Instant.now(), Instant.now());
    }

    private StockLevel level(UUID productId, UUID storeId, int qty) {
        return new StockLevel(UUID.randomUUID(), productId, null, storeId, qty, Instant.now());
    }

    private StockMovement movement(MovementType type) {
        return new StockMovement(UUID.randomUUID(), PRODUCT_ID, null, SRC_STORE_ID,
            type, 10, -5, 5, ACTOR_ID, null, Instant.now());
    }

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test01");
        validationStrategy = new DefaultTransferValidationStrategy();
        service = new ExecuteTransferService(
            storeRepository, stockLevelRepository, stockOperationService,
            transferRepository, validationStrategy, eventPublisher
        );
    }

    @Test
    void execute_shouldDecrement_sourceStock() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));
        when(storeRepository.findById(DEST_STORE_ID)).thenReturn(Optional.of(activeStore(DEST_STORE_ID)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, SRC_STORE_ID))
            .thenReturn(Optional.of(level(PRODUCT_ID, SRC_STORE_ID, 20)));
        when(stockOperationService.recordOperation(eq(PRODUCT_ID), isNull(), eq(SRC_STORE_ID),
            eq(MovementType.TRANSFER_OUT), eq(-5), eq(ACTOR_ID), any()))
            .thenReturn(movement(MovementType.TRANSFER_OUT));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(cmd);

        verify(stockOperationService).recordOperation(
            eq(PRODUCT_ID), isNull(), eq(SRC_STORE_ID), eq(MovementType.TRANSFER_OUT), eq(-5), eq(ACTOR_ID), any());
    }

    @Test
    void execute_shouldNotCreditDestinationStock_untilReceived() {
        // In the 2-step flow, TRANSFER_IN on destination is deferred to CompleteTransferService.
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));
        when(storeRepository.findById(DEST_STORE_ID)).thenReturn(Optional.of(activeStore(DEST_STORE_ID)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, SRC_STORE_ID))
            .thenReturn(Optional.of(level(PRODUCT_ID, SRC_STORE_ID, 20)));
        when(stockOperationService.recordOperation(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(movement(MovementType.TRANSFER_OUT));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(cmd);

        verify(stockOperationService, never()).recordOperation(
            eq(PRODUCT_ID), isNull(), eq(DEST_STORE_ID), eq(MovementType.TRANSFER_IN), anyInt(), any(), any());
    }

    @Test
    void execute_shouldPersistTransferRecord() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, "notes");
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));
        when(storeRepository.findById(DEST_STORE_ID)).thenReturn(Optional.of(activeStore(DEST_STORE_ID)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, SRC_STORE_ID))
            .thenReturn(Optional.of(level(PRODUCT_ID, SRC_STORE_ID, 20)));
        when(stockOperationService.recordOperation(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(movement(MovementType.TRANSFER_OUT));

        ArgumentCaptor<StockTransfer> captor = ArgumentCaptor.forClass(StockTransfer.class);
        when(transferRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(cmd);

        StockTransfer saved = captor.getValue();
        assertThat(saved.getSourceStoreId()).isEqualTo(SRC_STORE_ID);
        assertThat(saved.getDestinationStoreId()).isEqualTo(DEST_STORE_ID);
        assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getQuantity()).isEqualTo(5);
        assertThat(saved.getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(saved.getNotes()).isEqualTo("notes");
    }

    @Test
    void execute_shouldPublishStockTransferredEvent() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));
        when(storeRepository.findById(DEST_STORE_ID)).thenReturn(Optional.of(activeStore(DEST_STORE_ID)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, SRC_STORE_ID))
            .thenReturn(Optional.of(level(PRODUCT_ID, SRC_STORE_ID, 20)));
        when(stockOperationService.recordOperation(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(movement(MovementType.TRANSFER_OUT));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(cmd);

        ArgumentCaptor<StockTransferredEvent> eventCaptor = ArgumentCaptor.forClass(StockTransferredEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        StockTransferredEvent event = eventCaptor.getValue();
        assertThat(event.sourceStoreId()).isEqualTo(SRC_STORE_ID);
        assertThat(event.destinationStoreId()).isEqualTo(DEST_STORE_ID);
        assertThat(event.productId()).isEqualTo(PRODUCT_ID);
        assertThat(event.quantity()).isEqualTo(5);
    }

    @Test
    void execute_shouldCreateOneMovementRecord_TRANSFER_OUT_only() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));
        when(storeRepository.findById(DEST_STORE_ID)).thenReturn(Optional.of(activeStore(DEST_STORE_ID)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, SRC_STORE_ID))
            .thenReturn(Optional.of(level(PRODUCT_ID, SRC_STORE_ID, 20)));
        when(stockOperationService.recordOperation(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(movement(MovementType.TRANSFER_OUT));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(cmd);

        verify(stockOperationService, times(1)).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_shouldThrow_INSUFFICIENT_STOCK_whenSourceQtyTooLow() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 50, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));
        when(storeRepository.findById(DEST_STORE_ID)).thenReturn(Optional.of(activeStore(DEST_STORE_ID)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, SRC_STORE_ID))
            .thenReturn(Optional.of(level(PRODUCT_ID, SRC_STORE_ID, 10)));

        assertThatThrownBy(() -> service.execute(cmd))
            .isInstanceOf(DomainException.class)
            .extracting("domainCode")
            .isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void execute_shouldThrow_STORE_NOT_FOUND_whenSourceStoreDoesNotExist() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(cmd))
            .isInstanceOf(DomainException.class)
            .extracting("domainCode")
            .isEqualTo("STORE_NOT_FOUND");
    }

    @Test
    void execute_shouldThrow_STORE_NOT_FOUND_whenDestinationStoreDoesNotExist() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, DEST_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));
        when(storeRepository.findById(DEST_STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(cmd))
            .isInstanceOf(DomainException.class)
            .extracting("domainCode")
            .isEqualTo("STORE_NOT_FOUND");
    }

    @Test
    void execute_shouldThrow_SAME_SOURCE_DESTINATION_whenStoreIdsMatch() {
        var cmd = new TransferStockCommand(SRC_STORE_ID, SRC_STORE_ID, PRODUCT_ID, null, 5, ACTOR_ID, null);
        when(storeRepository.findById(SRC_STORE_ID)).thenReturn(Optional.of(activeStore(SRC_STORE_ID)));

        assertThatThrownBy(() -> service.execute(cmd))
            .isInstanceOf(DomainException.class)
            .extracting("domainCode")
            .isEqualTo("SAME_SOURCE_DESTINATION");
    }
}
