package com.keevo.commerce.sale.application;

import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.api.StockQueryPort;
import com.keevo.inventory.api.StockCommandPort;
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
    @Mock private StockQueryPort stockQuery;
    @Mock private StockCommandPort stockCommand;
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
                saleRepository, productStatusPort, stockQuery,
                stockCommand, eventPublisher, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        // Story v1s-13-10: the cascade claims PENDING_VALIDATION→COMPLETED atomically; default = won.
        lenient().when(saleRepository.updateStatusIfCurrent(
                any(), eq(SaleStatus.PENDING_VALIDATION), eq(SaleStatus.COMPLETED))).thenReturn(true);
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


    @Test
    void onProductActivated_allProductsActive_withStock_validatesSale() {
        Sale pending = pendingSaleWith(PRODUCT_A, PRODUCT_B);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(productStatusPort.getProductStatus(PRODUCT_B)).thenReturn("ACTIVE");
        when(stockQuery.availableQuantity(PRODUCT_A, null, STORE_ID)).thenReturn(10);
        when(stockQuery.availableQuantity(PRODUCT_B, null, STORE_ID)).thenReturn(5);

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);
        verify(stockCommand, times(2)).recordMovement(argThat(m -> java.util.Objects.equals(m.storeId(), STORE_ID)
                && java.util.Objects.equals(m.actorId(), ACTOR_ID)
                && !m.allowNegative()));

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleAutoValidatedEvent);
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleCompletedEvent);
        // v1s-18-6: the audit snapshot carries the real items, not a hard-coded "[]"
        SaleCompletedEvent completed = eventCaptor.getAllValues().stream()
                .filter(SaleCompletedEvent.class::isInstance).map(SaleCompletedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(completed.getItemsSnapshot())
                .contains(PRODUCT_A.toString())
                .contains(PRODUCT_B.toString());
    }

    @Test
    void onProductActivated_someProductsStillDraft_saleStaysPending() {
        Sale pending = pendingSaleWith(PRODUCT_A, PRODUCT_B);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(productStatusPort.getProductStatus(PRODUCT_B)).thenReturn("DRAFT");

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verifyNoInteractions(stockCommand);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onProductActivated_allProductsActive_zeroStock_saleStaysPending() {
        Sale pending = pendingSaleWith(PRODUCT_A);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(stockQuery.availableQuantity(PRODUCT_A, null, STORE_ID)).thenReturn(0);

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verifyNoInteractions(stockCommand);
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
        when(stockQuery.availableQuantity(PRODUCT_A, null, STORE_ID)).thenReturn(20);

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);
        verify(saleRepository).updateStatusIfCurrent(saleId2, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);
    }

    @Test
    void onProductActivated_noAffectedSales_noOp() {
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of());

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onStockEntry_triggersRecheck_validatesSale() {
        Sale pending = pendingSaleWith(PRODUCT_A);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(stockQuery.availableQuantity(PRODUCT_A, null, STORE_ID)).thenReturn(5);

        service.onStockChanged(PRODUCT_A, STORE_ID, ACTOR_ID);

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);
    }

    // ── Story v1s-13-10 ─────────────────────────────────────────────────────

    @Test
    void cascade_saleMovementIdsAreSaleItemIds() {
        Sale pending = pendingSaleWith(PRODUCT_A, PRODUCT_B);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(any())).thenReturn("ACTIVE");
        when(stockQuery.availableQuantity(any(), isNull(), eq(STORE_ID))).thenReturn(10);

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        for (SaleItem item : pending.getItems()) {
            verify(stockCommand).recordMovement(argThat(m -> java.util.Objects.equals(m.productId(), item.getProductId())
                && java.util.Objects.equals(m.storeId(), STORE_ID)
                && java.util.Objects.equals(m.kind(), StockMovementKind.SALE)
                && m.quantityChange() == -item.getQuantity()
                && java.util.Objects.equals(m.actorId(), ACTOR_ID)
                && java.util.Objects.equals(m.movementId(), item.getId())
                && !m.allowNegative()));
        }
    }

    // Another actor settled the sale between the cascade's read and its claim: skip it —
    // no stock movement, no event, and the activation itself does not fail.
    @Test
    void cascade_lostClaim_skipsSale_noStockNoEvent() {
        Sale pending = pendingSaleWith(PRODUCT_A);
        when(saleRepository.findPendingByProductId(PRODUCT_A)).thenReturn(List.of(pending));
        when(productStatusPort.getProductStatus(PRODUCT_A)).thenReturn("ACTIVE");
        when(stockQuery.availableQuantity(PRODUCT_A, null, STORE_ID)).thenReturn(10);
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED))
                .thenReturn(false);

        service.onProductActivated(PRODUCT_A, ACTOR_ID);

        verifyNoInteractions(stockCommand);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
