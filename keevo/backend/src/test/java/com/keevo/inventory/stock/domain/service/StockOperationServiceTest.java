package com.keevo.inventory.stock.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.adapter.in.api.CatalogProductApiTestSupport;
import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.entity.StockMovement;
import com.keevo.inventory.stock.domain.event.StockAdjustedEvent;
import com.keevo.inventory.stock.domain.event.StockThresholdBreachedEvent;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.stock.domain.port.out.StockMovementRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

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
    @Mock private ProductQueryPort        productQuery;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.keevo.store.api.StoreQueryPort storeQuery; // Story 14.10 (store :: api since 12.2d)

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
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private ProductView productWithThreshold(int threshold) {
        return CatalogProductApiTestSupport.view(new Product(PRODUCT_ID, "Widget", "desc", "KEV-ABCDEF",
            UUID.randomUUID(), 1000, 800, 50, 100,
            false, ProductStatus.ACTIVE, threshold,
            Instant.now(), Instant.now()));
    }

    private StockLevel levelWithQuantity(int qty) {
        return new StockLevel(UUID.randomUUID(), PRODUCT_ID, null, STORE_ID, qty, Instant.now());
    }

    // ── GREEN: happy path ──────────────────────────────────────────────────

    @Test
    void should_record_stock_entry_and_update_level() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
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
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
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
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
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
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(5)));
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
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
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
    @DisplayName("AC4: should propagate OptimisticLockingFailureException on concurrent stock mutation")
    void shouldNotLoseUpdateOnConcurrentStockMutation() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        // Save movement succeeds, but save level throws — simulating concurrent write
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any()))
            .thenThrow(new OptimisticLockingFailureException("Row was updated or deleted by another transaction"));

        assertThatThrownBy(() -> service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 5, ACTOR_ID, "delivery"))
            .isInstanceOf(OptimisticLockingFailureException.class);

        // No events should be published (transaction rolls back)
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── Offline sync: negative stock allowed on request, client movement id ──

    @Test
    void allowNegative_appliesTheDeltaBelowZero() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(2)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StockMovement result = service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.SALE, -3, ACTOR_ID, "offline sale", null, true);

        assertThat(result.getQuantityAfter()).isEqualTo(-1);
        verify(stockLevelRepository).save(argThat(l -> l.getQuantity() == -1));
    }

    @Test
    void negativeLevel_canBeReadAndRestocked() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(-2)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StockMovement result = service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 5, ACTOR_ID, "delivery");

        assertThat(result.getQuantityAfter()).isEqualTo(3);
    }

    @Test
    void clientMovementId_isKeptAsTheMovementId() {
        UUID movementId = UUID.randomUUID();
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StockMovement result = service.recordOperation(
            PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 5, ACTOR_ID, "delivery", movementId, false);

        assertThat(result.getId()).isEqualTo(movementId);
    }

    // ── v1s-15-9: actor label fallback via CurrentActor ─────────────────────

    @Test
    void employeeWithoutFirstName_eventCarriesFallbackLabelNotUuid() {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ACTOR_ID, null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        auth.setDetails(com.keevo.shared.infrastructure.security.AuthDetails.employee(null, STORE_ID));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordOperation(PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 5, ACTOR_ID, "delivery");

        ArgumentCaptor<StockAdjustedEvent> captor = ArgumentCaptor.forClass(StockAdjustedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorName()).isEqualTo("Un employé").doesNotContain(ACTOR_ID.toString());
        assertThat(captor.getValue().actorRole()).isEqualTo("EMPLOYEE");
    }

    // ── v1s-16-23 — counterpart store of a transfer ──────────────────────────

    @Test
    void transferLeg_eventCarriesCounterpartStoreName() {
        UUID dest = UUID.randomUUID();
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storeQuery.findById(TENANT, dest)).thenReturn(Optional.of(new com.keevo.store.api.StoreView(
            dest, "Boutique B", com.keevo.store.api.StoreType.STORE, null, null, true,
            java.time.Instant.now(), java.time.Instant.now())));

        service.recordOperation(PRODUCT_ID, null, STORE_ID, MovementType.TRANSFER_OUT, -3, ACTOR_ID, null, dest);

        ArgumentCaptor<StockAdjustedEvent> captor = ArgumentCaptor.forClass(StockAdjustedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().counterpartStoreName()).isEqualTo("Boutique B");
    }

    @Test
    void transferLeg_missingCounterpartStore_noNameNoFailure() {
        UUID dest = UUID.randomUUID();
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storeQuery.findById(TENANT, dest)).thenReturn(Optional.empty());

        service.recordOperation(PRODUCT_ID, null, STORE_ID, MovementType.TRANSFER_OUT, -3, ACTOR_ID, null, dest);

        ArgumentCaptor<StockAdjustedEvent> captor = ArgumentCaptor.forClass(StockAdjustedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().counterpartStoreName()).isNull();
    }

    @Test
    void nonTransfer_eventHasNoCounterpart() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(productWithThreshold(0)));
        when(stockLevelRepository.findByProductAndStore(PRODUCT_ID, STORE_ID))
            .thenReturn(Optional.of(levelWithQuantity(10)));
        when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordOperation(PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY, 5, ACTOR_ID, "delivery");

        ArgumentCaptor<StockAdjustedEvent> captor = ArgumentCaptor.forClass(StockAdjustedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().counterpartStoreName()).isNull();
    }
}
