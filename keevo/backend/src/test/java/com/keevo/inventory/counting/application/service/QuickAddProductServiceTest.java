package com.keevo.inventory.counting.application.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.counting.domain.event.InventoryCountSavedEvent;
import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductCommand;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("QuickAddProductService — atomic quick-add during inventory")
class QuickAddProductServiceTest {

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
    private static final String TENANT = "tenant_test";

    private InventorySession inProgressSession;

    @BeforeEach
    void setUp() {
        service = new QuickAddProductService(
                sessionRepository, productRepository, stockLevelRepository,
                countRepository, eventPublisher);
        TenantContext.setCurrentTenant(TENANT);

        inProgressSession = new InventorySession(
                SESSION_ID, STORE_ID, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, ACTOR_ID, Instant.now(),
                null, null, null, Instant.now());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Happy path — creates product + stock level + inventory count atomically")
    void execute_happyPath_createsAllThreeEntities() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productRepository.existsByName("Robe Wax L")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new QuickAddProductCommand(SESSION_ID, "Robe Wax L", CATEGORY_ID, 5, null, ACTOR_ID);
        QuickAddProductResult result = service.execute(cmd);

        assertThat(result.product().getName()).isEqualTo("Robe Wax L");
        assertThat(result.product().getSku()).matches("^KEV-[A-Z0-9]{6}$");
        assertThat(result.product().getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(result.product().getPriceValue()).isZero();

        assertThat(result.stockLevel().getProductId()).isEqualTo(result.product().getId());
        assertThat(result.stockLevel().getStoreId()).isEqualTo(STORE_ID);
        assertThat(result.stockLevel().getQuantity()).isEqualTo(5);

        assertThat(result.inventoryCount().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(result.inventoryCount().getProductId()).isEqualTo(result.product().getId());
        assertThat(result.inventoryCount().getTheoretical()).isZero();
        assertThat(result.inventoryCount().getPhysical()).isEqualTo(5);

        verify(productRepository).save(any(Product.class));
        verify(stockLevelRepository).save(any(StockLevel.class));
        verify(countRepository).save(any(InventoryCount.class));
    }

    @Test
    @DisplayName("Dedup blocks creation when product name already exists")
    void execute_duplicateName_throwsProductNameAlreadyExists() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productRepository.existsByName("Robe Wax L")).thenReturn(true);

        var cmd = new QuickAddProductCommand(SESSION_ID, "Robe Wax L", CATEGORY_ID, 5, null, ACTOR_ID);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS.name()));

        verify(productRepository, never()).save(any());
        verify(stockLevelRepository, never()).save(any());
        verify(countRepository, never()).save(any());
    }

    @Test
    @DisplayName("Session not found throws INVENTORY_SESSION_NOT_FOUND")
    void execute_sessionNotFound_throws() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        var cmd = new QuickAddProductCommand(SESSION_ID, "Test", CATEGORY_ID, 1, null, ACTOR_ID);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("Session not IN_PROGRESS throws INVENTORY_SESSION_NOT_IN_PROGRESS")
    void execute_sessionNotInProgress_throws() {
        InventorySession cancelledSession = new InventorySession(
                SESSION_ID, STORE_ID, InventoryScope.FULL, null,
                InventorySessionStatus.CANCELLED, ACTOR_ID, Instant.now(),
                ACTOR_ID, Instant.now(), null, Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(cancelledSession));

        var cmd = new QuickAddProductCommand(SESSION_ID, "Test", CATEGORY_ID, 1, null, ACTOR_ID);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS.name()));
    }

    @Test
    @DisplayName("Name is trimmed before dedup check and product creation")
    void execute_nameWithSpaces_isTrimmed() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productRepository.existsByName("Chapeau")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new QuickAddProductCommand(SESSION_ID, "  Chapeau  ", CATEGORY_ID, 3, null, ACTOR_ID);
        QuickAddProductResult result = service.execute(cmd);

        assertThat(result.product().getName()).isEqualTo("Chapeau");
        verify(productRepository).existsByName("Chapeau");
    }

    @Test
    @DisplayName("Publishes ProductCreatedEvent and InventoryCountSavedEvent on success")
    void execute_happyPath_publishesBothEvents() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productRepository.existsByName("Sac")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new QuickAddProductCommand(SESSION_ID, "Sac", CATEGORY_ID, 2, null, ACTOR_ID);
        service.execute(cmd);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

        var events = eventCaptor.getAllValues();
        assertThat(events.get(0)).isInstanceOf(ProductCreatedEvent.class);
        ProductCreatedEvent prodEvent = (ProductCreatedEvent) events.get(0);
        assertThat(prodEvent.productName()).isEqualTo("Sac");
        assertThat(prodEvent.tenantId()).isEqualTo(TENANT);
        assertThat(prodEvent.actorId()).isEqualTo(ACTOR_ID);

        assertThat(events.get(1)).isInstanceOf(InventoryCountSavedEvent.class);
        InventoryCountSavedEvent countEvent = (InventoryCountSavedEvent) events.get(1);
        assertThat(countEvent.sessionId()).isEqualTo(SESSION_ID);
        assertThat(countEvent.theoretical()).isZero();
        assertThat(countEvent.physical()).isEqualTo(2);
        assertThat(countEvent.ecart()).isEqualTo(2);
    }
}
