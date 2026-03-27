package com.keevo.inventory.counting.application.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportQuery;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateGapReportServiceTest {

    @Mock private InventorySessionRepository sessionRepo;
    @Mock private InventoryCountRepository countRepo;
    @Mock private ProductRepository productRepo;
    @Mock private StoreRepository storeRepo;

    private GenerateGapReportService service;

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID PRODUCT_A_ID = UUID.randomUUID();
    private static final UUID PRODUCT_B_ID = UUID.randomUUID();
    private static final UUID PRODUCT_C_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GenerateGapReportService(sessionRepo, countRepo, productRepo, storeRepo);
    }

    @Test
    void execute_happyPath_shouldBuildCompleteReport() {
        InventorySession session = createSession();
        when(sessionRepo.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(countRepo.findBySessionId(SESSION_ID)).thenReturn(List.of(
                InventoryCount.create(SESSION_ID, PRODUCT_A_ID, null, "Concordant", null, 10, 10, ACTOR_ID),
                InventoryCount.create(SESSION_ID, PRODUCT_B_ID, null, "Shortage", null, 10, 5, ACTOR_ID),
                InventoryCount.create(SESSION_ID, PRODUCT_C_ID, null, "Surplus", null, 5, 8, ACTOR_ID)
        ));
        when(productRepo.findAllByIds(anyList())).thenReturn(List.of(
                product(PRODUCT_A_ID, "Concordant", 5000),
                product(PRODUCT_B_ID, "Shortage", 5000),
                product(PRODUCT_C_ID, "Surplus", 3000)
        ));
        when(storeRepo.findById(STORE_ID)).thenReturn(Optional.of(store()));

        InventoryGapReport report = service.execute(new GenerateGapReportQuery(SESSION_ID, ACTOR_ID));

        assertThat(report.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(report.getStoreName()).isEqualTo("Boutique Centrale");
        assertThat(report.getSummary().totalCounted()).isEqualTo(3);
        assertThat(report.getSummary().totalConcordant()).isEqualTo(1);
        assertThat(report.getSummary().totalShortage()).isEqualTo(1);
        assertThat(report.getSummary().totalSurplus()).isEqualTo(1);
        assertThat(report.getShortageRows()).hasSize(1);
        assertThat(report.getShortageRows().get(0).gapValueXaf()).isEqualTo(25000L);
    }

    @Test
    void execute_sessionNotFound_shouldThrow() {
        when(sessionRepo.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(new GenerateGapReportQuery(SESSION_ID, ACTOR_ID)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void execute_emptyCounts_shouldReturnEmptyReport() {
        when(sessionRepo.findById(SESSION_ID)).thenReturn(Optional.of(createSession()));
        when(countRepo.findBySessionId(SESSION_ID)).thenReturn(List.of());
        when(productRepo.findAllByIds(anyList())).thenReturn(List.of());
        when(storeRepo.findById(STORE_ID)).thenReturn(Optional.of(store()));

        InventoryGapReport report = service.execute(new GenerateGapReportQuery(SESSION_ID, ACTOR_ID));

        assertThat(report.getSummary().totalCounted()).isZero();
    }

    @Test
    void execute_uncountedProductsSkipped() {
        when(sessionRepo.findById(SESSION_ID)).thenReturn(Optional.of(createSession()));
        // an uncounted product has physical=null
        InventoryCount uncounted = new InventoryCount(
                UUID.randomUUID(), SESSION_ID, PRODUCT_A_ID, null,
                "Uncounted", null, 10, null, null, null, Instant.now());
        InventoryCount counted = InventoryCount.create(SESSION_ID, PRODUCT_B_ID, null, "Counted", null, 10, 10, ACTOR_ID);
        when(countRepo.findBySessionId(SESSION_ID)).thenReturn(List.of(uncounted, counted));
        when(productRepo.findAllByIds(anyList())).thenReturn(List.of(product(PRODUCT_B_ID, "Counted", 3000)));
        when(storeRepo.findById(STORE_ID)).thenReturn(Optional.of(store()));

        InventoryGapReport report = service.execute(new GenerateGapReportQuery(SESSION_ID, ACTOR_ID));

        assertThat(report.getSummary().totalCounted()).isEqualTo(1);
    }

    @Test
    void execute_productPriceZero_shouldCalculateZeroValue() {
        when(sessionRepo.findById(SESSION_ID)).thenReturn(Optional.of(createSession()));
        when(countRepo.findBySessionId(SESSION_ID)).thenReturn(List.of(
                InventoryCount.create(SESSION_ID, PRODUCT_A_ID, null, "FreeProduct", null, 10, 5, ACTOR_ID)
        ));
        when(productRepo.findAllByIds(anyList())).thenReturn(List.of(product(PRODUCT_A_ID, "FreeProduct", 0)));
        when(storeRepo.findById(STORE_ID)).thenReturn(Optional.of(store()));

        InventoryGapReport report = service.execute(new GenerateGapReportQuery(SESSION_ID, ACTOR_ID));

        assertThat(report.getShortageRows()).hasSize(1);
        assertThat(report.getShortageRows().get(0).gapValueXaf()).isZero();
    }

    // ── Helpers ──

    private InventorySession createSession() {
        return new InventorySession(SESSION_ID, STORE_ID, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, ACTOR_ID, Instant.now(),
                null, null, null, Instant.now());
    }

    private Store store() {
        return new Store(STORE_ID, "Boutique Centrale", StoreType.STORE,
                null, null, true, Instant.now(), Instant.now());
    }

    private Product product(UUID id, String name, int price) {
        return new Product(id, name, null, "KEV-" + name.substring(0, Math.min(6, name.length())).toUpperCase().replaceAll("[^A-Z0-9]", "X").concat("XXXXXX").substring(0, 6),
                UUID.randomUUID(), price, 0, 0, 0, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
    }
}
