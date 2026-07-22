package com.keevo.commerce.sale.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.application.service.CorrectSaleService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase.CorrectSaleCommand;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorrectSaleServiceTest {

    @Mock private SaleRepository saleRepository;
    @Mock private StockOperationService stockOperationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CorrectSaleService service;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final Instant NOW = Instant.now();
    private static final String JUSTIFICATION = "Erreur de quantité scannée au comptoir";

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test");
        service = new CorrectSaleService(saleRepository, stockOperationService, eventPublisher, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID itemId;

    private Sale completedSale(int quantity, int discountAmount) {
        itemId = itemId != null ? itemId : UUID.randomUUID();
        var item = new SaleItem(itemId, SALE_ID, PRODUCT_A, null, "Produit A", 1000, 1000, quantity);
        int subtotal = 1000 * quantity;
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, subtotal - discountAmount, discountAmount,
                SaleStatus.COMPLETED, NOW, NOW, List.of(item));
    }

    @Test
    void correctSale_decreaseQuantity_restoresStockAndRecalculatesTotal() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 0)));

        service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of(itemId, 2)));

        verify(stockOperationService).recordOperation(
                eq(PRODUCT_A), any(), eq(STORE_ID), eq(MovementType.ADJUSTMENT),
                eq(3), eq(ACTOR_ID), any());

        var captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualTo(2000);
        assertThat(captor.getValue().getItems().get(0).getQuantity()).isEqualTo(2);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SaleCorrectedEvent.class);
    }

    @Test
    void correctSale_increaseQuantity_decrementsStock() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(2, 0)));

        service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of(itemId, 5)));

        verify(stockOperationService).recordOperation(
                eq(PRODUCT_A), any(), eq(STORE_ID), eq(MovementType.ADJUSTMENT),
                eq(-3), eq(ACTOR_ID), any());

        var captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualTo(5000);
    }

    @Test
    void correctSale_increaseBeyondAvailable_throwsInsufficientStock() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(2, 0)));
        doThrow(new DomainException(com.keevo.shared.domain.exception.ErrorCode.INSUFFICIENT_STOCK, "insufficient"))
                .when(stockOperationService).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of(itemId, 100))))
                .isInstanceOf(DomainException.class);

        verify(saleRepository, never()).save(any());
    }

    @Test
    void correctSale_notCompleted_throwsSaleNotCompleted() {
        var pending = new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 1000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW,
                List.of(new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_A, null, "Produit A", 1000, 1000, 1)));
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of(UUID.randomUUID(), 2))))
                .isInstanceOf(DomainException.class);

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(saleRepository, never()).save(any());
    }

    @Test
    void correctSale_blankJustification_throwsJustificationRequired() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 0)));

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                "   ", Map.of(itemId, 2))))
                .isInstanceOf(DomainException.class);

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(saleRepository, never()).save(any());
    }

    @Test
    void correctSale_zeroOrNegativeQuantity_throwsValidationError() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 0)));

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of(itemId, 0))))
                .isInstanceOf(DomainException.class);

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(saleRepository, never()).save(any());
    }

    @Test
    void correctSale_discountNowExceedsSubtotal_throwsDiscountExceedsSubtotal() {
        // discount=4000, quantity 5 @1000 = subtotal 5000, total=1000. Decrease qty to 2 → subtotal 2000 < discount.
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 4000)));

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of(itemId, 2))))
                .isInstanceOf(DomainException.class);

        verify(saleRepository, never()).save(any());
    }

    @Test
    void correctSale_unknownItemId_isNoOp() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 0)));

        service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of(UUID.randomUUID(), 9)));

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        var captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(captor.capture());
        assertThat(captor.getValue().getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void correctSale_nullItemQuantities_throwsValidationError() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 0)));

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, null)))
                .isInstanceOf(DomainException.class);

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(saleRepository, never()).save(any());
    }

    @Test
    void correctSale_emptyItemQuantities_throwsValidationError() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 0)));

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, null,
                JUSTIFICATION, Map.of())))
                .isInstanceOf(DomainException.class);

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(saleRepository, never()).save(any());
    }

    @Test
    void correctSale_employeeOutsideAssignedStore_throwsForbidden() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale(5, 0)));
        UUID otherStoreId = UUID.randomUUID();

        assertThatThrownBy(() -> service.correctSale(new CorrectSaleCommand(SALE_ID, ACTOR_ID, otherStoreId,
                JUSTIFICATION, Map.of(itemId, 2))))
                .isInstanceOf(DomainException.class);

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(saleRepository, never()).save(any());
    }
}
