package com.keevo.catalog.stock.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.event.StockAdjustedEvent;
import com.keevo.catalog.stock.domain.event.StockThresholdBreachedEvent;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.port.out.StockMovementRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
 * StockOperationServiceTest — unit test for the domain service.
 *
 * <p>All dependencies mocked. Validates:
 * <ul>
 *   <li>Happy path: movement recorded, level updated, event published</li>
 *   <li>First stock entry: level created at 0</li>
 *   <li>INSUFFICIENT_STOCK guard</li>
 *   <li>Threshold breach event</li>
 *   <li>PRODUCT_NOT_FOUND guard</li>
 * </ul>
 *
 * Story 2.3.
 */
@ExtendWith(MockitoExtension.class)
class StockOperationServiceTest {

    @Mock private StockLevelRepository     stockLevelRepository;
    @Mock private StockMovementRepository  stockMovementRepository;
    @Mock private ProductRepository        productRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private StockOperationService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();
    private static final UUID ACTOR_ID   = UUID.randomUUID();
    private static final String TENANT   = "tenant_test";

    @BeforeEach
    void setTenantContext() {
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private Product productWithThreshold(int threshold) {
        return new Product(PRODUCT_ID, "Widget", "desc", "KEV-ABCDEF",
            UUID.randomUUID(), 1000, 800, 50, 100,
            false, ProductStatus.ACTIVE, threshold,
            Instant.now(), Instant.now());
    }

    private StockLevel levelWithQuantity(int qty) {
        return new StockLevel(UUID.randomUUID(), PRODUCT_ID, null, STORE_ID, qty, Instant.now());
    }

    // ── GREEN: happy path ──────────────────────────────────────────────────

    @Test
    void should_record_stock_entry_and_update_level() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StockMovement result = service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 5, ACTOR_ID, "delivery");

        assertThat(result.getQuantityBefore()).isEqualTo(10);
        assertThat(result.getQuantityChange()).isEqualTo(5);
        assertThat(result.getQuantityAfter()).isEqualTo(15);
        assertThat(result.getMovementType()).isEqualTo(MovementType.STOCK_ENTRY);

        verify(stockMovementRepository).save(any(StockMovement.class));
        verify(stockLevelRepository).save(argThat(l -> l.getQuantity() == 15));
        verify(eventPublisher).publishEvent(any(StockAdjustedEvent.class));
    }

    @Test
    void should_create_stock_level_at_zero_when_first_operation() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.empty());  // no level yet
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StockMovement result = service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 8, ACTOR_ID, null);

        assertThat(result.getQuantityBefore()).isEqualTo(0);
        assertThat(result.getQuantityAfter()).isEqualTo(8);
    }

    // ── RED → GREEN: guard INSUFFICIENT_STOCK ─────────────────────────────

    @Test
    void should_throw_insufficient_stock_when_quantity_would_go_negative() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(3)));

        assertThatThrownBy(() -> service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.SALE, -10, ACTOR_ID, null))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        verify(stockMovementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── RED → GREEN: threshold breach event ───────────────────────────────

    @Test
    void should_publish_threshold_breached_event_when_quantity_falls_to_threshold() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(5)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Sell 5: 10 → 5, which equals threshold of 5
        service.recordOperation(PRODUCT_ID, null, STORE_ID, MovementType.SALE, -5, ACTOR_ID, null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());

        boolean thresholdEventPublished = captor.getAllValues().stream()
            .anyMatch(e -> e instanceof StockThresholdBreachedEvent);
        assertThat(thresholdEventPublished).isTrue();
    }

    @Test
    void should_not_publish_threshold_breached_when_threshold_is_zero() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(1)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordOperation(PRODUCT_ID, null, STORE_ID, MovementType.SALE, -1, ACTOR_ID, null);

        // Only StockAdjustedEvent, NOT StockThresholdBreachedEvent
        verify(eventPublisher, times(1)).publishEvent(any(StockAdjustedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(StockThresholdBreachedEvent.class));
    }

    // ── RED → GREEN: PRODUCT_NOT_FOUND ────────────────────────────────────

    @Test
    void should_throw_product_not_found_when_product_does_not_exist() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 5, ACTOR_ID, null))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
