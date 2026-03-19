package com.keevo.commerce.sale.adapter.out.persistence;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleItemJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.impl.SaleRepositoryAdapter;
import com.keevo.commerce.sale.adapter.out.persistence.jpa.SaleSpringRepository;
import com.keevo.commerce.sale.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class SaleRepositoryAdapterTest {

    @Autowired
    private SaleSpringRepository springRepository;

    private SaleRepositoryAdapter adapter;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new SaleRepositoryAdapter(springRepository);
    }

    private Sale sampleSale() {
        var item = new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_ID, null,
                "Produit A", 1500, 1500, 2);
        return new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of(item));
    }

    @Test
    void save_persistsSaleWithItems() {
        adapter.save(sampleSale());

        var found = springRepository.findById(SALE_ID);
        assertThat(found).isPresent();
        assertThat(found.get().getTotalAmount()).isEqualTo(3000);
        assertThat(found.get().getItems()).hasSize(1);
    }

    @Test
    void findById_returnsSaleWithItems() {
        adapter.save(sampleSale());

        var result = adapter.findById(SALE_ID);
        assertThat(result).isPresent();
        assertThat(result.get().getItems()).hasSize(1);
        assertThat(result.get().getItems().get(0).getProductName()).isEqualTo("Produit A");
    }

    @Test
    void existsById_returnsTrueForExistingSale() {
        adapter.save(sampleSale());
        assertThat(adapter.existsById(SALE_ID)).isTrue();
        assertThat(adapter.existsById(UUID.randomUUID())).isFalse();
    }

    // ── Story 4.2 — Discount persistence ─────────────────────────────────────

    @Test
    void save_persistsSaleWithDiscountAmount() {
        var item = new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_ID, null,
                "Produit A", 5000, 5000, 2);
        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 9500, 500, SaleStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of(item));

        adapter.save(sale);

        var found = springRepository.findById(SALE_ID);
        assertThat(found).isPresent();
        assertThat(found.get().getDiscountAmount()).isEqualTo(500);
        assertThat(found.get().getTotalAmount()).isEqualTo(9500);
    }

    // ── Story 4.4 — Sales History queries for Day Closure ─────────────────────

    @Test
    void findByStoreIdAndEmployeeIdAndDateRange_returnsMatchingSales() {
        // Given - two sales: one within range, one outside
        Instant now = Instant.now();
        Instant startOfDay = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plusSeconds(86400); // +24h

        // Sale within range
        var item1 = new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_ID, null,
                "Produit A", 1500, 1500, 2);
        var saleInRange = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.COMPLETED,
                now, now, List.of(item1));
        adapter.save(saleInRange);

        // Sale outside range (yesterday)
        UUID saleId2 = UUID.randomUUID();
        Instant yesterday = now.minusSeconds(86400 * 2);
        var item2 = new SaleItem(UUID.randomUUID(), saleId2, PRODUCT_ID, null,
                "Produit B", 2000, 2000, 1);
        var saleOutOfRange = new Sale(saleId2, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.MOBILE_MONEY, 2000, 0, SaleStatus.COMPLETED,
                yesterday, yesterday, List.of(item2));
        adapter.save(saleOutOfRange);

        // When
        var results = adapter.findByStoreIdAndEmployeeIdAndDateRange(
                STORE_ID, EMPLOYEE_ID, startOfDay, endOfDay,
                org.springframework.data.domain.PageRequest.of(0, 50));

        // Then - only the sale within the date range is returned
        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getId()).isEqualTo(SALE_ID);
    }

    @Test
    void findByStoreIdAndDateRange_returnsAllStoreSales() {
        // Given - sales from two employees in the same store
        Instant now = Instant.now();
        Instant startOfDay = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plusSeconds(86400);

        UUID employeeId2 = UUID.randomUUID();

        // Employee 1 sale
        var item1 = new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_ID, null,
                "Produit A", 1500, 1500, 1);
        var sale1 = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 1500, 0, SaleStatus.COMPLETED,
                now, now, List.of(item1));
        adapter.save(sale1);

        // Employee 2 sale
        UUID saleId2 = UUID.randomUUID();
        var item2 = new SaleItem(UUID.randomUUID(), saleId2, PRODUCT_ID, null,
                "Produit B", 3000, 3000, 1);
        var sale2 = new Sale(saleId2, STORE_ID, employeeId2, null,
                PaymentMode.MOBILE_MONEY, 3000, 0, SaleStatus.COMPLETED,
                now, now, List.of(item2));
        adapter.save(sale2);

        // When - OWNER query (all store sales)
        var results = adapter.findByStoreIdAndDateRange(
                STORE_ID, startOfDay, endOfDay,
                org.springframework.data.domain.PageRequest.of(0, 50));

        // Then - both employees' sales are returned
        assertThat(results.getContent()).hasSize(2);
    }

    @Test
    void findByStoreIdAndDateRangeAndStatus_filtersCorrectly() {
        // Given - one COMPLETED, one PENDING_VALIDATION, one CANCELLED
        Instant now = Instant.now();
        Instant startOfDay = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plusSeconds(86400);

        // COMPLETED sale
        var item1 = new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_ID, null,
                "Produit A", 1500, 1500, 1);
        var completedSale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 1500, 0, SaleStatus.COMPLETED,
                now, now, List.of(item1));
        adapter.save(completedSale);

        // PENDING_VALIDATION sale
        UUID saleId2 = UUID.randomUUID();
        var item2 = new SaleItem(UUID.randomUUID(), saleId2, PRODUCT_ID, null,
                "Produit B", 2000, 2000, 1);
        var pendingSale = new Sale(saleId2, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.MOBILE_MONEY, 2000, 0, SaleStatus.PENDING_VALIDATION,
                now, now, List.of(item2));
        adapter.save(pendingSale);

        // When - filter COMPLETED only
        var results = adapter.findByStoreIdAndDateRangeAndStatus(
                STORE_ID, startOfDay, endOfDay, SaleStatus.COMPLETED,
                org.springframework.data.domain.PageRequest.of(0, 50));

        // Then - only COMPLETED sale is returned
        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getStatus()).isEqualTo(SaleStatus.COMPLETED);
    }

    @Test
    void existsByStoreIdAndDateRangeAndStatus_returnsTrueWhenExists() {
        // Given - a COMPLETED sale today
        Instant now = Instant.now();
        Instant startOfDay = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plusSeconds(86400);

        var item = new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_ID, null,
                "Produit A", 1500, 1500, 1);
        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 1500, 0, SaleStatus.COMPLETED,
                now, now, List.of(item));
        adapter.save(sale);

        // When & Then
        assertThat(adapter.existsByStoreIdAndDateRangeAndStatus(
                STORE_ID, startOfDay, endOfDay, SaleStatus.COMPLETED)).isTrue();
        assertThat(adapter.existsByStoreIdAndDateRangeAndStatus(
                STORE_ID, startOfDay, endOfDay, SaleStatus.PENDING_VALIDATION)).isFalse();
        assertThat(adapter.existsByStoreIdAndDateRangeAndStatus(
                UUID.randomUUID(), startOfDay, endOfDay, SaleStatus.COMPLETED)).isFalse();
    }
}
