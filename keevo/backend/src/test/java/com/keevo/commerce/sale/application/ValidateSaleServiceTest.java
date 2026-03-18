package com.keevo.commerce.sale.application;

import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.application.service.ValidateSaleService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
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
class ValidateSaleServiceTest {

    @Mock private SaleRepository saleRepository;
    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private StockOperationService stockOperationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ValidateSaleService service;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test");
        service = new ValidateSaleService(
                saleRepository, stockLevelRepository, stockOperationService, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Sale pendingSale() {
        var items = List.of(
                new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_A, null, "Produit A", 3000, 3000, 2),
                new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_B, null, "Produit B", 5000, 5000, 1));
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 11000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW, items);
    }

    private Sale completedSale() {
        var items = List.of(
                new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_A, null, "Produit A", 3000, 3000, 1));
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.COMPLETED, NOW, NOW, items);
    }

    private StockLevel stockLevel(UUID productId, int qty) {
        return new StockLevel(UUID.randomUUID(), productId, null, STORE_ID, qty, Instant.now());
    }

    // ── Manual validation (AC9) ──────────────────────────────────

    @Test
    void manualValidate_withJustification_transitionsToCompleted() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_A, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_A, 20)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_B, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_B, 10)));

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID,
                "Client fidèle, produit confirmé par fournisseur"));

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.COMPLETED);
        verify(stockOperationService, times(2)).recordOperation(
                any(), any(), eq(STORE_ID), any(), anyInt(), eq(ACTOR_ID), any());

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleManuallyValidatedEvent);
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleCompletedEvent);
    }

    @Test
    void manualValidate_insufficientStock_forcesToZero_emitsStockForcedZeroEvent() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        // Product A: have 1, need 2 → force to 0
        when(stockLevelRepository.findByProductAndStore(PRODUCT_A, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_A, 1)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_B, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_B, 10)));

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID,
                "Validation forcée malgré stock insuffisant"));

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.COMPLETED);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof StockForcedZeroEvent);
    }

    @Test
    void manualValidate_withoutJustification_succeeds() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_A, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_A, 20)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_B, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_B, 10)));

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID, null));

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.COMPLETED);
    }

    @Test
    void manualValidate_shortJustification_throws() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        assertThatThrownBy(() -> service.validateSale(
                new ValidateSaleCommand(SALE_ID, ACTOR_ID, "trop crt")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void manualValidate_alreadyCompleted_throws() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale()));

        assertThatThrownBy(() -> service.validateSale(
                new ValidateSaleCommand(SALE_ID, ACTOR_ID, "Trying to validate completed sale")))
                .isInstanceOf(DomainException.class);
    }

    // ── Cancellation (AC10) ──────────────────────────────────────

    @Test
    void cancelPendingSale_withJustification_transitionsToCancelled() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID,
                "Produit finalement non disponible chez le fournisseur"));

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.CANCELLED);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SaleCancelledEvent.class);
    }

    @Test
    void cancelPendingSale_noStockRestoration() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID,
                "Stock jamais décrémenté en PENDING"));

        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void cancelPendingSale_withoutJustification_succeeds() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null));

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.CANCELLED);
    }

    @Test
    void cancelPendingSale_notPending_throws() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale()));

        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, "Trying to cancel completed")))
                .isInstanceOf(DomainException.class);
    }
}
