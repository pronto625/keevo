package com.keevo.commerce.sale.application;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.commerce.sale.application.service.CloseDayService;
import com.keevo.commerce.sale.application.service.DayClosureSummaryBuilder;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TDD RED tests for CloseDayService.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@ExtendWith(MockitoExtension.class)
class CloseDayServiceTest {

    @Mock
    private SaleRepository saleRepository;

    @Mock
    private DayClosureRepository dayClosureRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CloseDayService service;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        service = new CloseDayService(saleRepository, dayClosureRepository, eventPublisher);
    }

    @Test
    void closeDayService_givenSalesForDay_computesCorrectSummary() {
        // Given - 3 COMPLETED sales today
        List<Sale> salesForDay = List.of(
                createSale(15000, 0, PaymentMode.CASH, SaleStatus.COMPLETED),
                createSale(20000, 0, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED),
                createSale(10000, 0, PaymentMode.CASH, SaleStatus.COMPLETED)
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findByStoreIdAndDateRange(eq(STORE_ID), any(), any(), any()))
                .thenAnswer(inv -> {
                    return new org.springframework.data.domain.PageImpl<>(salesForDay);
                });

        // When
        var result = service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then
        assertThat(result.getSummary().totalSales()).isEqualTo(3);
        assertThat(result.getSummary().totalRevenue()).isEqualTo(45000); // 15000 + 20000 + 10000
        assertThat(result.getSummary().cashAmount()).isEqualTo(25000);   // 15000 + 10000
        assertThat(result.getSummary().momoAmount()).isEqualTo(20000);
    }

    @Test
    void closeDayService_excludesPendingValidationFromRevenue() {
        // Given - 2 COMPLETED + 1 PENDING_VALIDATION
        List<Sale> allSalesForDay = List.of(
                createSale(15000, 0, PaymentMode.CASH, SaleStatus.COMPLETED),
                createSale(20000, 0, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED),
                createSale(10000, 0, PaymentMode.CASH, SaleStatus.PENDING_VALIDATION) // excluded from revenue
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findByStoreIdAndDateRange(eq(STORE_ID), any(), any(), any()))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(allSalesForDay));

        // When
        var result = service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then - PENDING_VALIDATION excluded from totalRevenue but counted in pendingSales
        assertThat(result.getSummary().totalSales()).isEqualTo(2);           // only COMPLETED
        assertThat(result.getSummary().totalRevenue()).isEqualTo(35000);     // 15000 + 20000 (no 10000 pending)
        assertThat(result.getSummary().pendingSalesCount()).isEqualTo(1);
        assertThat(result.getSummary().pendingSalesTotal()).isEqualTo(10000);
    }

    @Test
    void closeDayService_publishesDayClosedEvent() {
        // Given
        List<Sale> salesForDay = List.of(
                createSale(15000, 0, PaymentMode.CASH, SaleStatus.COMPLETED)
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findByStoreIdAndDateRange(eq(STORE_ID), any(), any(), any()))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(salesForDay));

        // When
        service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then
        var eventCaptor = ArgumentCaptor.forClass(DayClosedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        DayClosedEvent event = eventCaptor.getValue();
        assertThat(event.storeId()).isEqualTo(STORE_ID);
        assertThat(event.actorId()).isEqualTo(ACTOR_ID);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        assertThat(event.isAutomatic()).isFalse();
        assertThat(event.summary().totalSales()).isEqualTo(1);
    }

    @Test
    void closeDayService_alreadyClosed_throwsDomainException() {
        // Given - closure already exists for today
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAY_ALREADY_CLOSED);
    }

    @Test
    void closeDayService_savesClosureToRepository() {
        // Given
        List<Sale> salesForDay = List.of(
                createSale(10000, 0, PaymentMode.CASH, SaleStatus.COMPLETED)
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findByStoreIdAndDateRange(eq(STORE_ID), any(), any(), any()))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(salesForDay));

        // When
        service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then
        var closureCaptor = ArgumentCaptor.forClass(DayClosure.class);
        verify(dayClosureRepository).save(closureCaptor.capture());

        DayClosure saved = closureCaptor.getValue();
        assertThat(saved.getStoreId()).isEqualTo(STORE_ID);
        assertThat(saved.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
    }

    // ── Story 7.6: Calendar window tests ─────────────────────────────────────

    @Test
    void windowIsFullCalendarDay_forManualClose() {
        // Given - manual closure; window = today WAT 00:00 → 23:59:59.999999999
        ZoneId wat = ZoneId.of("Africa/Lagos");
        LocalDate today = LocalDate.now(wat);
        Instant expectedStart = today.atStartOfDay(wat).toInstant();
        Instant expectedEnd = today.atTime(LocalTime.MAX).atZone(wat).toInstant();

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findByStoreIdAndDateRange(eq(STORE_ID), any(), any(), any()))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(List.of()));

        // When
        service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then
        var eventCaptor = ArgumentCaptor.forClass(DayClosedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        DayClosedEvent event = eventCaptor.getValue();
        assertThat(event.windowStart()).isEqualTo(expectedStart);
        assertThat(event.windowEnd()).isEqualTo(expectedEnd);
    }

    @Test
    void windowIsTodayCalendarDay_forAutoClose() {
        // Given - automatic closure (Story 13.3: closes TODAY at configured eodReportTime)
        ZoneId wat = ZoneId.of("Africa/Lagos");
        LocalDate today = LocalDate.now(wat);
        Instant expectedStart = today.atStartOfDay(wat).toInstant();
        Instant expectedEnd = today.atTime(LocalTime.MAX).atZone(wat).toInstant();

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findByStoreIdAndDateRange(eq(STORE_ID), any(), any(), any()))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(List.of()));

        // When
        service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, true));

        // Then
        var eventCaptor = ArgumentCaptor.forClass(DayClosedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        DayClosedEvent event = eventCaptor.getValue();
        assertThat(event.windowStart()).isEqualTo(expectedStart);
        assertThat(event.windowEnd()).isEqualTo(expectedEnd);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Sale createSale(int totalAmount, int discount, PaymentMode paymentMode, SaleStatus status) {
        UUID saleId = UUID.randomUUID();
        var item = new SaleItem(UUID.randomUUID(), saleId, UUID.randomUUID(), null,
                "Produit", totalAmount, totalAmount, 1);
        return new Sale(saleId, STORE_ID, ACTOR_ID, null,
                paymentMode, totalAmount - discount, discount, status,
                Instant.now(), Instant.now(), List.of(item));
    }
}
