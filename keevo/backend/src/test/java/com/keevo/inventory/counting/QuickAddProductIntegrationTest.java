package com.keevo.inventory.counting;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.counting.application.service.QuickAddProductService;
import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductCommand;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * QuickAddProductIntegrationTest — end-to-end service flow verification.
 * Tests the full facade coordination: session validation → dedup → product → stock → count → events.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickAddProduct — Integration (full stack flow)")
class QuickAddProductIntegrationTest {

    @Mock private InventorySessionRepository sessionRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private InventoryCountRepository countRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private QuickAddProductService service;

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new QuickAddProductService(
                sessionRepository, productRepository, stockLevelRepository,
                countRepository, eventPublisher);
        TenantContext.setCurrentTenant("tenant_integration");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Full stack happy path — session→dedup→product→stock→count→events all succeed")
    void fullStack_happyPath() {
        InventorySession session = new InventorySession(
                SESSION_ID, STORE_ID, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, ACTOR_ID, Instant.now(),
                null, null, null, Instant.now());

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(productRepository.existsByName("Sac à main")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new QuickAddProductCommand(SESSION_ID, "Sac à main", CATEGORY_ID, 10, null, ACTOR_ID);
        QuickAddProductResult result = service.execute(cmd);

        // Verify all 3 entities created
        assertThat(result.product()).isNotNull();
        assertThat(result.product().getName()).isEqualTo("Sac à main");
        assertThat(result.product().getSku()).matches("^KEV-[A-Z0-9]{6}$");
        assertThat(result.product().getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(result.product().getCategoryId()).isEqualTo(CATEGORY_ID);

        assertThat(result.stockLevel()).isNotNull();
        assertThat(result.stockLevel().getProductId()).isEqualTo(result.product().getId());
        assertThat(result.stockLevel().getStoreId()).isEqualTo(STORE_ID);
        assertThat(result.stockLevel().getQuantity()).isEqualTo(10);

        assertThat(result.inventoryCount()).isNotNull();
        assertThat(result.inventoryCount().getTheoretical()).isZero();
        assertThat(result.inventoryCount().getPhysical()).isEqualTo(10);
        assertThat(result.inventoryCount().getEcart()).isEqualTo(10);

        // Verify 2 events published
        verify(eventPublisher, times(2)).publishEvent(any(Object.class));
        // Verify all 3 repos called exactly once
        verify(productRepository).save(any(Product.class));
        verify(stockLevelRepository).save(any(StockLevel.class));
        verify(countRepository).save(any(InventoryCount.class));
    }

    @Test
    @DisplayName("Dedup blocks product with existing name — no writes to product/stock/count repos")
    void fullStack_dedupBlocks_noWritesOccur() {
        InventorySession session = new InventorySession(
                SESSION_ID, STORE_ID, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, ACTOR_ID, Instant.now(),
                null, null, null, Instant.now());

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(productRepository.existsByName("Existing")).thenReturn(true);

        var cmd = new QuickAddProductCommand(SESSION_ID, "Existing", CATEGORY_ID, 3, null, ACTOR_ID);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS.name()));

        // No writes should have occurred
        verify(productRepository, never()).save(any());
        verify(stockLevelRepository, never()).save(any());
        verify(countRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Partial failure in stock save — exception propagates (triggers @Transactional rollback)")
    void fullStack_stockSaveFails_exceptionPropagates() {
        InventorySession session = new InventorySession(
                SESSION_ID, STORE_ID, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, ACTOR_ID, Instant.now(),
                null, null, null, Instant.now());

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(productRepository.existsByName("Fail Test")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any(StockLevel.class)))
                .thenThrow(new RuntimeException("DB constraint violation"));

        var cmd = new QuickAddProductCommand(SESSION_ID, "Fail Test", CATEGORY_ID, 1, null, ACTOR_ID);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB constraint violation");

        // Product was saved but stock failed — in real @Transactional scenario, product save would rollback
        verify(productRepository).save(any(Product.class));
        verify(stockLevelRepository).save(any(StockLevel.class));
        verify(countRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
