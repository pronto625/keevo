package com.keevo.commerce.sale.application;

import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.application.service.SaleValidationCascadeService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.out.ProductStatusPort;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SaleValidationCascadeServiceTest {

    @Mock private SaleRepository saleRepository;
    @Mock private ProductStatusPort productStatusPort;
    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private StockOperationService stockOperationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SaleValidationCascadeService service;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test");
        service = new SaleValidationCascadeService(
                saleRepository, productStatusPort, stockLevelRepository,
                stockOperationService, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Sale pendingSaleWith(UUID... productIds) {
        List<SaleItem> items = java.util.Arrays.stream(productIds)
                .map(pid -> new SaleItem(UUID.randomUUID(), SALE_ID, pid, null,
                        "Prod-" + pid.toString().substring(0, 4), 3000, 3000, 1))
                .toList();
        int total = items.stream().mapToInt(SaleItem::getSubtotal).sum();
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, total, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW, items);
    }

    private StockLevel stockLevel(UUID productId, int qty) {
        return new StockLevel(UUID.randomUUID(), productId, null, STORE_ID, qty, Instant.now());
    }

    @Test
    void onProductActivated_allProductsActive_withStock_validatesSale() {
        Sale pending = pendingSaleWith(PRODUCT_A, PRODUCT_B);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(productStatusPort.getProductStatus(PRODUCT_B)).thenReturn("ACTIVE");
        when(stockLevelRepository.findByProductAndStore(PRODUCT_A, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_A, 10)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_B, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_B, 5)));

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.COMPLETED);
        verify(stockOperationService, times(2)).recordOperation(
                any(), any(), eq(STORE_ID), any(), anyInt(), eq(ACTOR_ID), any());

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleAutoValidatedEvent);
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleCompletedEvent);
    }

    @Test
    void onProductActivated_someProductsStillDraft_saleStaysPending() {
        Sale pending = pendingSaleWith(PRODUCT_A, PRODUCT_B);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(productStatusPort.getProductStatus(PRODUCT_B)).thenReturn("DRAFT");

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository, never()).updateStatus(any(), any());
        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onProductActivated_allProductsActive_zeroStock_saleStaysPending() {
        Sale pending = pendingSaleWith(PRODUCT_A);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(stockLevelRepository.findByProductAndStore(PRODUCT_A, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_A, 0)));

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository, never()).updateStatus(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onProductActivated_multipleAffectedSales_validatesAll() {
        UUID saleId2 = UUID.randomUUID();
        Sale pending1 = pendingSaleWith(PRODUCT_A);
        Sale pending2 = new Sale(saleId2, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW,
                List.of(new SaleItem(UUID.randomUUID(), saleId2, PRODUCT_A, null,
                        "Prod-A", 3000, 3000, 1)));

        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending1, pending2));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(stockLevelRepository.findByProductAndStore(PRODUCT_A, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_A, 20)));

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.COMPLETED);
        verify(saleRepository).updateStatus(saleId2, SaleStatus.COMPLETED);
    }

    @Test
    void onProductActivated_noAffectedSales_noOp() {
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of());

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository, never()).updateStatus(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onStockEntry_triggersRecheck_validatesSale() {
        Sale pending = pendingSaleWith(PRODUCT_A);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(stockLevelRepository.findByProductAndStore(PRODUCT_A, STORE_ID))
                .thenReturn(Optional.of(stockLevel(PRODUCT_A, 5)));

        service.onStockChanged(PRODUCT_A, STORE_ID, ACTOR_ID);

        verify(saleRepository).updateStatus(SALE_ID, SaleStatus.COMPLETED);
    }
}
