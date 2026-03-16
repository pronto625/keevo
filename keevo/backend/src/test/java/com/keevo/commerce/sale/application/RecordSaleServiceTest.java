package com.keevo.commerce.sale.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.application.service.RecordSaleService;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.SaleCompletedEvent;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
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
        service = new RecordSaleService(
                saleRepository, stockLevelRepository, stockOperationService,
                eventPublisher, new ObjectMapper());
    }

    private RecordSaleCommand validCommand() {
        return new RecordSaleCommand(
                SALE_ID, ACTOR_ID, STORE_ID, null,
                PaymentMode.CASH, null,
                List.of(new SaleItemCommand(PRODUCT_ID, null, "Produit A", 1500, 2))
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
}
