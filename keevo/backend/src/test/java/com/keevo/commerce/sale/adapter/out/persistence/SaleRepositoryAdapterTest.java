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
}
