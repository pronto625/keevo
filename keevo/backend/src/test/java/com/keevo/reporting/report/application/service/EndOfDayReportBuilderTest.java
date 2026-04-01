package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * EndOfDayReportBuilderTest — TDD GREEN tests for data assembly from SQL.
 * Story 7.2 — Task 1.2.
 * Uses EntityManager.createNativeQuery() (JPA Hibernate-managed) per architecture rules.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EndOfDayReportBuilderTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private EndOfDayReportBuilder builder;
    private UUID storeId;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        builder = new EndOfDayReportBuilder(entityManager);
        storeId = UUID.randomUUID();
        date = LocalDate.of(2026, 5, 15);

        // Default: every createNativeQuery returns the same mock query
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        lenient().when(query.getResultList()).thenReturn(List.of());
        lenient().when(query.getSingleResult()).thenReturn(0);
    }

    /**
     * Stubs getSingleResult() to return values in call order:
     * countCompletedSales, sumRevenue, sumByPaymentMode(CASH), sumByPaymentMode(MOBILE_MONEY),
     * countPendingSales, sumPendingRevenue, countLowStock.
     */
    private void stubAllQueries(int sales, int revenue, int cash, int momo,
                                 int pending, int pendingRev, int lowStock) {
        when(query.getSingleResult()).thenReturn(sales, revenue, cash, momo, pending, pendingRev, lowStock);
    }

    @Test
    void build_shouldReturnCorrectStoreName() {
        stubAllQueries(5, 100000, 70000, 30000, 0, 0, 2);
        EndOfDayReportData data = builder.build(storeId, Instant.EPOCH, Instant.now(), "Ma Boutique", date, LocalTime.of(20, 0), false);
        assertThat(data.storeName()).isEqualTo("Ma Boutique");
    }

    @Test
    void build_shouldReturnCorrectReportDate() {
        stubAllQueries(5, 100000, 70000, 30000, 0, 0, 2);
        EndOfDayReportData data = builder.build(storeId, Instant.EPOCH, Instant.now(), "Test", date, LocalTime.NOON, false);
        assertThat(data.reportDate()).isEqualTo(date);
    }

    @Test
    void build_shouldReturnIsAutomatic() {
        stubAllQueries(0, 0, 0, 0, 0, 0, 0);
        EndOfDayReportData data = builder.build(storeId, Instant.EPOCH, Instant.now(), "T", date, LocalTime.NOON, true);
        assertThat(data.isAutomatic()).isTrue();
    }

    @Test
    void build_whenNoSales_avgBasketShouldBeZero() {
        stubAllQueries(0, 0, 0, 0, 0, 0, 0);
        EndOfDayReportData data = builder.build(storeId, Instant.EPOCH, Instant.now(), "T", date, LocalTime.NOON, false);
        assertThat(data.avgBasket()).isZero();
    }

    @Test
    void build_shouldReturnLowStockCount() {
        stubAllQueries(3, 60000, 40000, 20000, 0, 0, 4);
        EndOfDayReportData data = builder.build(storeId, Instant.EPOCH, Instant.now(), "T", date, LocalTime.NOON, false);
        assertThat(data.lowStockCount()).isEqualTo(4);
    }
}
