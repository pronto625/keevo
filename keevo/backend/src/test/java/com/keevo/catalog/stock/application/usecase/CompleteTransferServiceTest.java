package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.event.StockTransferredEvent;
import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import com.keevo.catalog.stock.domain.port.in.CompleteTransferCommand;
import com.keevo.catalog.stock.domain.port.out.StockTransferRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CompleteTransferServiceTest — verifies idempotent completion via conditional UPDATE (Option A).
 *
 * <p>Story v1s-13-1 — B-CRIT-1: prevents double-credit on concurrent transfer completion.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CompleteTransferService — idempotent completion (Option A)")
class CompleteTransferServiceTest {

    @Mock private StockTransferRepository transferRepository;
    @Mock private StockOperationService stockOperationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CompleteTransferService service;

    private static final UUID TRANSFER_ID          = UUID.randomUUID();
    private static final UUID SOURCE_STORE_ID      = UUID.randomUUID();
    private static final UUID DESTINATION_STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID           = UUID.randomUUID();
    private static final UUID ACTOR_ID             = UUID.randomUUID();
    private static final String TENANT             = "test_tenant";

    @BeforeEach
    void setTenantContext() {
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private StockTransfer createInTransitTransfer() {
        return new StockTransfer(TRANSFER_ID, SOURCE_STORE_ID, DESTINATION_STORE_ID,
                PRODUCT_ID, null, 10, ACTOR_ID, Instant.now(),
                TransferStatus.IN_TRANSIT, "test notes");
    }

    private StockTransfer createCompletedTransfer() {
        return new StockTransfer(TRANSFER_ID, SOURCE_STORE_ID, DESTINATION_STORE_ID,
                PRODUCT_ID, null, 10, ACTOR_ID, Instant.now(),
                TransferStatus.COMPLETED, "test notes");
    }

    // ── AC3: should not double-credit on concurrent transfer completion ─────

    @Test
    @DisplayName("AC3: should not double-credit on concurrent transfer completion")
    void shouldNotDoubleCreditOnConcurrentTransferComplete() {
        // First call: finds transfer IN_TRANSIT, transitionStatus succeeds, completes normally
        var transfer = createInTransitTransfer();
        var completed = createCompletedTransfer();

        when(transferRepository.findById(TRANSFER_ID)).thenReturn(Optional.of(transfer));
        when(transferRepository.transitionStatus(TRANSFER_ID, TransferStatus.IN_TRANSIT, TransferStatus.COMPLETED))
                .thenReturn(true);
        when(stockOperationService.recordOperation(
                eq(PRODUCT_ID), eq(null), eq(DESTINATION_STORE_ID),
                eq(MovementType.TRANSFER_IN), eq(10), eq(ACTOR_ID), anyString()))
                .thenReturn(null);
        when(transferRepository.findById(TRANSFER_ID))
                .thenReturn(Optional.of(transfer))   // first findById
                .thenReturn(Optional.of(completed)); // re-fetch after transition

        StockTransfer result = service.execute(new CompleteTransferCommand(TRANSFER_ID, ACTOR_ID));

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        // Verify recordOperation was called exactly ONCE
        verify(stockOperationService, times(1)).recordOperation(
                eq(PRODUCT_ID), eq(null), eq(DESTINATION_STORE_ID),
                eq(MovementType.TRANSFER_IN), eq(10), eq(ACTOR_ID), anyString());

        // Verify event was published exactly ONCE
        verify(eventPublisher, times(1)).publishEvent(any(StockTransferredEvent.class));
    }

    // ── AC3: idempotent — already COMPLETED returns existing ─────────────────

    @Test
    @DisplayName("AC3: should return existing transfer on duplicate reception (idempotent)")
    void shouldReturnExistingTransferOnDuplicateReception() {
        var transfer = createInTransitTransfer();
        var completed = createCompletedTransfer();

        // Step 1 gets IN_TRANSIT; re-fetch after failed transition gets COMPLETED (idempotent)
        when(transferRepository.findById(TRANSFER_ID))
                .thenReturn(Optional.of(transfer))    // step 1: IN_TRANSIT
                .thenReturn(Optional.of(completed));  // re-fetch: COMPLETED
        when(transferRepository.transitionStatus(TRANSFER_ID, TransferStatus.IN_TRANSIT, TransferStatus.COMPLETED))
                .thenReturn(false);

        StockTransfer result = service.execute(new CompleteTransferCommand(TRANSFER_ID, ACTOR_ID));

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        // recordOperation must NOT be called (no double credit)
        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());

        // event must NOT be published (no duplicate audit)
        verify(eventPublisher, never()).publishEvent(any(StockTransferredEvent.class));
    }

    // ── TRANSFER_NOT_FOUND ───────────────────────────────────────────────────

    @Test
    @DisplayName("should throw TRANSFER_NOT_FOUND when transfer does not exist")
    void shouldThrowTransferNotFound() {
        when(transferRepository.findById(TRANSFER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(new CompleteTransferCommand(TRANSFER_ID, ACTOR_ID)))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.TRANSFER_NOT_FOUND.name());
    }

    // ── TRANSFER_INVALID_STATUS (transition failed + status is not COMPLETED) ─

    @Test
    @DisplayName("should throw TRANSFER_INVALID_STATUS when status is CONFLICT (not IN_TRANSIT)")
    void shouldThrowInvalidStatusWhenConflict() {
        var transfer = createInTransitTransfer();
        var conflict = new StockTransfer(TRANSFER_ID, SOURCE_STORE_ID, DESTINATION_STORE_ID,
                PRODUCT_ID, null, 10, ACTOR_ID, Instant.now(),
                TransferStatus.CONFLICT, "test notes");

        // Step 1 gets IN_TRANSIT; re-fetch after failed transition gets CONFLICT
        when(transferRepository.findById(TRANSFER_ID))
                .thenReturn(Optional.of(transfer))    // step 1: IN_TRANSIT
                .thenReturn(Optional.of(conflict));   // re-fetch: CONFLICT
        when(transferRepository.transitionStatus(TRANSFER_ID, TransferStatus.IN_TRANSIT, TransferStatus.COMPLETED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.execute(new CompleteTransferCommand(TRANSFER_ID, ACTOR_ID)))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.TRANSFER_INVALID_STATUS.name());

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
