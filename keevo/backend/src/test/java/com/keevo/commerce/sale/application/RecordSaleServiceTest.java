package com.keevo.commerce.sale.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.domain.event.SalePriceOverriddenEvent;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.application.service.RecordSaleService;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.SaleCompletedEvent;
import com.keevo.commerce.sale.domain.model.SalePendingValidationEvent;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecordSaleServiceTest {

    @Mock private SaleRepository saleRepository;
    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private StockOperationService stockOperationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RecordSaleService service;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_abc123");
        service = new RecordSaleService(
                saleRepository, stockLevelRepository, stockOperationService,
                eventPublisher, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RecordSaleCommand validCommand() {
        return new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null, 0,
                List.of(new SaleItemCommand(null, PRODUCT_ID, null, "Produit A", 1500, 1500, 2))
        );
    }

    private StockLevel stockLevel(int qty) {
        return new StockLevel(UUID.randomUUID(), PRODUCT_ID, null, STORE_ID, qty, Instant.now());
    }

    @Test
    void recordSale_success_savesAndPublishesEvent() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        service.recordSale(validCommand());

        verify(saleRepository).save(any());
        verify(stockOperationService).recordOperation(
                eq(PRODUCT_ID), eq(null), eq(STORE_ID),
                any(), eq(-2), eq(ACTOR_ID), any());

        ArgumentCaptor<SaleCompletedEvent> eventCaptor = ArgumentCaptor.forClass(SaleCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getSaleId()).isEqualTo(SALE_ID);
        assertThat(eventCaptor.getValue().getTotalAmount()).isEqualTo(3000);
    }

    @Test
    void recordSale_insufficientStock_throwsDomainException() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(1))); // only 1 available, need 2

        assertThatThrownBy(() -> service.recordSale(validCommand()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("INSUFFICIENT_STOCK");

        verify(saleRepository, never()).save(any());
    }

    @Test
    void recordSale_stockNotFound_throwsDomainException() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.empty()); // no stock entry → 0 available, need 2

        assertThatThrownBy(() -> service.recordSale(validCommand()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("INSUFFICIENT_STOCK");
    }

    @Test
    void recordSale_idempotency_duplicateSaleIdIsNoOp() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(true);

        service.recordSale(validCommand());

        verify(saleRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void recordSale_setsStatusToCompleted() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        service.recordSale(validCommand());

        var saleCaptor = ArgumentCaptor.forClass(com.keevo.commerce.sale.domain.model.Sale.class);
        verify(saleRepository).save(saleCaptor.capture());
        assertThat(saleCaptor.getValue().getStatus())
                .isEqualTo(com.keevo.commerce.sale.domain.model.SaleStatus.COMPLETED);
    }

    // ── Story 4.2 — Discount + Price Override tests ──────────────────────────

    @Test
    void recordSale_withDiscount_savesDiscountAmount() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        var command = new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null, 500,
                List.of(new SaleItemCommand(null, PRODUCT_ID, null, "Produit A", 1500, 1500, 2))
        );

        service.recordSale(command);

        var saleCaptor = ArgumentCaptor.forClass(com.keevo.commerce.sale.domain.model.Sale.class);
        verify(saleRepository).save(saleCaptor.capture());
        assertThat(saleCaptor.getValue().getDiscountAmount()).isEqualTo(500);
        assertThat(saleCaptor.getValue().getTotalAmount()).isEqualTo(2500); // 3000 - 500
    }

    @Test
    void recordSale_withPriceOverride_publishesSalePriceOverriddenEvent() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        // catalogueUnitPrice=5000, appliedUnitPrice=4000 → price override
        var command = new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null, 0,
                List.of(new SaleItemCommand(null, PRODUCT_ID, null, "Produit Test", 5000, 4000, 1))
        );

        service.recordSale(command);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

        var overrideEvents = eventCaptor.getAllValues().stream()
                .filter(e -> e instanceof SalePriceOverriddenEvent)
                .map(e -> (SalePriceOverriddenEvent) e)
                .toList();
        assertThat(overrideEvents).hasSize(1);
        assertThat(overrideEvents.get(0).cataloguePrice()).isEqualTo(5000);
        assertThat(overrideEvents.get(0).appliedPrice()).isEqualTo(4000);
        assertThat(overrideEvents.get(0).productName()).isEqualTo("Produit Test");
    }

    @Test
    void recordSale_noPriceOverride_doesNotPublishOverriddenEvent() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        // same catalogue and applied → no override
        var command = new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null, 0,
                List.of(new SaleItemCommand(null, PRODUCT_ID, null, "Produit A", 1500, 1500, 2))
        );

        service.recordSale(command);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).noneMatch(e -> e instanceof SalePriceOverriddenEvent);
    }

    @Test
    void recordSale_withDiscountAmount_setsCorrectTotalAmount() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        var command = new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null, 1000,
                List.of(new SaleItemCommand(null, PRODUCT_ID, null, "Produit A", 5000, 5000, 2))
        );

        service.recordSale(command);

        var saleCaptor = ArgumentCaptor.forClass(com.keevo.commerce.sale.domain.model.Sale.class);
        verify(saleRepository).save(saleCaptor.capture());
        // subtotal = 10000, discountAmount = 1000, totalAmount = 9000
        assertThat(saleCaptor.getValue().getTotalAmount()).isEqualTo(9000);
    }

    @Test
    void recordSale_publishesSaleCompletedEvent_withDiscountAmount() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        var command = new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null, 500,
                List.of(new SaleItemCommand(null, PRODUCT_ID, null, "Produit A", 1500, 1500, 2))
        );

        service.recordSale(command);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

        var completedEvents = eventCaptor.getAllValues().stream()
                .filter(e -> e instanceof SaleCompletedEvent)
                .map(e -> (SaleCompletedEvent) e)
                .toList();
        assertThat(completedEvents).hasSize(1);
        assertThat(completedEvents.get(0).getDiscountAmount()).isEqualTo(500);
        assertThat(completedEvents.get(0).getTotalAmount()).isEqualTo(2500); // 3000 - 500
    }

    // ── Story 4.3 — PENDING_VALIDATION tests ────────────────────────────────

    private RecordSaleCommand pendingCommand() {
        return new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null, 0,
                SaleStatus.PENDING_VALIDATION,
                List.of(new SaleItemCommand(null, PRODUCT_ID, null, "Produit Draft", 3000, 3000, 2))
        );
    }

    @Test
    void recordSale_withPendingValidation_doesNotDecrementStock() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);

        service.recordSale(pendingCommand());

        verify(saleRepository).save(any());
        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(stockLevelRepository, never()).findByProductAndStore(any(), any());
    }

    @Test
    void recordSale_withPendingValidation_publishesSalePendingValidationEvent() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);

        service.recordSale(pendingCommand());

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SalePendingValidationEvent);
        assertThat(eventCaptor.getAllValues()).noneMatch(e -> e instanceof SaleCompletedEvent);
    }

    @Test
    void recordSale_withPendingValidation_noStockAvailabilityCheck() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);

        // Should NOT throw INSUFFICIENT_STOCK — draft products have stock=0
        service.recordSale(pendingCommand());

        verify(saleRepository).save(any());
    }

    @Test
    void recordSale_withCompleted_existingBehaviorUnchanged() {
        when(saleRepository.existsById(SALE_ID)).thenReturn(false);
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
                .thenReturn(Optional.of(stockLevel(10)));

        service.recordSale(validCommand());

        verify(saleRepository).save(any());
        verify(stockOperationService).recordOperation(
                eq(PRODUCT_ID), any(), eq(STORE_ID), any(), eq(-2), eq(ACTOR_ID), any());
        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleCompletedEvent);
    }
}
