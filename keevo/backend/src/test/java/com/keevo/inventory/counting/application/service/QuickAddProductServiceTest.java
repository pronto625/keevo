package com.keevo.inventory.counting.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.api.RegisterProductInput;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.shared.event.catalog.counting.InventoryCountSavedEvent;
import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductCommand;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.api.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuickAddProductService — atomic quick-add during inventory")
class QuickAddProductServiceTest {

    @Mock private InventorySessionRepository sessionRepository;
    @Mock private ProductQueryPort productQuery;
    @Mock private ProductCommandPort productCommand;
    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private InventoryCountRepository countRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.keevo.store.store.domain.port.out.StoreRepository storeRepository;
    @Mock private ProductCountPort productCountPort;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanLimitGuard planLimitGuard;
    @Mock private TransactionLockPort transactionLock;
    @Mock private Subscription freeSubscription;

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
                sessionRepository, productQuery, productCommand, stockLevelRepository,
                countRepository, eventPublisher, StoreApiTestSupport.queryPort(storeRepository),
                productCountPort, SubscriptionApiTestSupport.queryPort(subscriptionRepository), SubscriptionApiTestSupport.limitPort(planLimitGuard), transactionLock);
        TenantContext.setCurrentTenant(TENANT);

        // v1s-14-13: active FREE plan under the cap (lenient: early-failure tests never reach it)
        lenient().when(freeSubscription.getPlanType()).thenReturn(PlanType.FREE);
        lenient().when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSubscription));
        lenient().when(productCountPort.countActiveProducts(any())).thenReturn(499);

        inProgressSession = new InventorySession(
                SESSION_ID, STORE_ID, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, ACTOR_ID, Instant.now(),
                null, null, null, Instant.now());
    }

    /** Echoes what the catalog persists for a registered product (ACTIVE, no stock, no threshold). */
    private static ProductView registered(RegisterProductInput in) {
        return new ProductView(in.id(), in.name(), null, in.sku(), in.categoryId(), in.price(),
                0, 0, 0, false, ProductState.ACTIVE, 0, in.createdAt(), in.createdAt());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Happy path — creates product + stock level + inventory count atomically")
    void execute_happyPath_createsAllThreeEntities() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Robe Wax L")).thenReturn(false);
        when(productCommand.register(any(), any())).thenAnswer(inv -> registered(inv.getArgument(1)));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new QuickAddProductCommand(SESSION_ID, "Robe Wax L", CATEGORY_ID, 5, null, ACTOR_ID);
        QuickAddProductResult result = service.execute(cmd);

        assertThat(result.product().name()).isEqualTo("Robe Wax L");
        assertThat(result.product().sku()).matches("^KEV-[A-Z0-9]{6}$");
        assertThat(result.product().categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(result.product().price()).isZero();

        assertThat(result.stockLevel().getProductId()).isEqualTo(result.product().id());
        assertThat(result.stockLevel().getStoreId()).isEqualTo(STORE_ID);
        // J18 — no stock at creation: validation applies the count once (0 → 5).
        assertThat(result.stockLevel().getQuantity()).isZero();
        assertThat(result.product().stockQuantity()).isZero();

        assertThat(result.inventoryCount().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(result.inventoryCount().getProductId()).isEqualTo(result.product().id());
        assertThat(result.inventoryCount().getTheoretical()).isZero();
        assertThat(result.inventoryCount().getPhysical()).isEqualTo(5);

        verify(productCommand).register(any(), any());
        verify(stockLevelRepository).save(any(StockLevel.class));
        verify(countRepository).save(any(InventoryCount.class));
    }

    @Test
    @DisplayName("Dedup blocks creation when product name already exists")
    void execute_duplicateName_throwsProductNameAlreadyExists() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Robe Wax L")).thenReturn(true);

        var cmd = new QuickAddProductCommand(SESSION_ID, "Robe Wax L", CATEGORY_ID, 5, null, ACTOR_ID);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS.name()));

        verify(productCommand, never()).register(any(), any());
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
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Chapeau")).thenReturn(false);
        when(productCommand.register(any(), any())).thenAnswer(inv -> registered(inv.getArgument(1)));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new QuickAddProductCommand(SESSION_ID, "  Chapeau  ", CATEGORY_ID, 3, null, ACTOR_ID);
        QuickAddProductResult result = service.execute(cmd);

        assertThat(result.product().name()).isEqualTo("Chapeau");
        verify(productQuery).existsByName(TenantContext.getCurrentTenant(), "Chapeau");
    }

    @Test
    @DisplayName("Registers the product with its actor context (catalog publishes ProductCreatedEvent) and publishes InventoryCountSavedEvent")
    void execute_happyPath_publishesBothEvents() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Sac")).thenReturn(false);
        when(productCommand.register(any(), any())).thenAnswer(inv -> registered(inv.getArgument(1)));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new QuickAddProductCommand(SESSION_ID, "Sac", CATEGORY_ID, 2, null, ACTOR_ID);
        service.execute(cmd);

        // 12.2b: ProductCreatedEvent is the catalog's — published by register, fed by these fields
        ArgumentCaptor<RegisterProductInput> registerCaptor = ArgumentCaptor.forClass(RegisterProductInput.class);
        verify(productCommand).register(any(), registerCaptor.capture());
        assertThat(registerCaptor.getValue().name()).isEqualTo("Sac");
        assertThat(registerCaptor.getValue().actorId()).isEqualTo(ACTOR_ID);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        var events = eventCaptor.getAllValues();
        assertThat(events.get(0)).isInstanceOf(InventoryCountSavedEvent.class);
        InventoryCountSavedEvent countEvent = (InventoryCountSavedEvent) events.get(0);
        assertThat(countEvent.sessionId()).isEqualTo(SESSION_ID);
        assertThat(countEvent.theoretical()).isZero();
        assertThat(countEvent.physical()).isEqualTo(2);
        assertThat(countEvent.ecart()).isEqualTo(2);
    }

    // ── v1s-15-9: storeName + centralised actor extraction ───────────────

    /** The register input carries the ProductCreatedEvent's actor context (12.2b). */
    private RegisterProductInput quickAddAndCaptureProductEvent() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Pagne")).thenReturn(false);
        when(productCommand.register(any(), any())).thenAnswer(inv -> registered(inv.getArgument(1)));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new QuickAddProductCommand(SESSION_ID, "Pagne", CATEGORY_ID, 1, null, ACTOR_ID));

        ArgumentCaptor<RegisterProductInput> captor = ArgumentCaptor.forClass(RegisterProductInput.class);
        verify(productCommand).register(any(), captor.capture());
        return captor.getValue();
    }

    private static void authenticateEmployee(String firstName) {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ACTOR_ID, null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        auth.setDetails(com.keevo.shared.infrastructure.security.AuthDetails.employee(firstName, STORE_ID));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("v1s-15-9 — the product-created context carries the session store's name")
    void execute_publishesStoreNameOfSessionStore() {
        authenticateEmployee("Awa");
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(new com.keevo.store.store.domain.model.Store(
                STORE_ID, "Boutique Akwa", com.keevo.store.api.StoreType.STORE,
                null, null, true, Instant.now(), Instant.now())));

        RegisterProductInput event = quickAddAndCaptureProductEvent();

        assertThat(event.storeName()).isEqualTo("Boutique Akwa");
        assertThat(event.actorRole()).isEqualTo("EMPLOYEE");
        assertThat(event.actorName()).isEqualTo("Awa");
    }

    @Test
    @DisplayName("v1s-15-9 — missing firstName claim falls back to 'Un employé', never the UUID")
    void execute_missingFirstName_usesFallbackLabel() {
        authenticateEmployee(null);

        RegisterProductInput event = quickAddAndCaptureProductEvent();

        assertThat(event.actorName()).isEqualTo("Un employé");
        assertThat(event.actorName()).doesNotContain(ACTOR_ID.toString());
    }

    // ── v1s-14-13: FREE plan product limit ───────────────────────────────

    @Test
    @DisplayName("v1s-14-13 — lock → session → name check → count/guard → save, in that order")
    void execute_takesLockFirst_thenChecksLimitBeforeSave() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Pagne")).thenReturn(false);
        when(productCommand.register(any(), any())).thenAnswer(inv -> registered(inv.getArgument(1)));
        when(stockLevelRepository.save(any(StockLevel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new QuickAddProductCommand(SESSION_ID, "Pagne", CATEGORY_ID, 1, null, ACTOR_ID));

        InOrder order = inOrder(transactionLock, sessionRepository, productQuery, productCommand,
                productCountPort, subscriptionRepository, planLimitGuard);
        order.verify(transactionLock).lock("product-limit:" + TENANT);
        order.verify(sessionRepository).findById(SESSION_ID);
        order.verify(productQuery).existsByName(TenantContext.getCurrentTenant(), "Pagne");
        order.verify(productCountPort).countActiveProducts(any());
        order.verify(subscriptionRepository).findActivePlan();
        order.verify(planLimitGuard).checkProductLimit(PlanType.FREE, 499);
        order.verify(productCommand).register(any(), any());
    }

    @Test
    @DisplayName("v1s-14-13 — at the cap → PLAN_LIMIT_EXCEEDED, nothing written, no event")
    void execute_atCap_throwsPlanLimitExceeded_writesNothing() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Pagne")).thenReturn(false);
        when(productCountPort.countActiveProducts(any())).thenReturn(500);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite de produits atteinte"))
                .when(planLimitGuard).checkProductLimit(PlanType.FREE, 500);

        var cmd = new QuickAddProductCommand(SESSION_ID, "Pagne", CATEGORY_ID, 1, null, ACTOR_ID);
        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name()));

        verify(transactionLock).lock("product-limit:" + TENANT);
        verify(productCommand, never()).register(any(), any());
        verify(stockLevelRepository, never()).save(any());
        verify(countRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("v1s-14-13 — no active subscription → SUBSCRIPTION_NOT_FOUND, nothing written")
    void execute_noSubscription_throwsSubscriptionNotFound() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Pagne")).thenReturn(false);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.empty());

        var cmd = new QuickAddProductCommand(SESSION_ID, "Pagne", CATEGORY_ID, 1, null, ACTOR_ID);
        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND.name()));

        verify(planLimitGuard, never()).checkProductLimit(any(), anyInt());
        verify(productCommand, never()).register(any(), any());
        verify(stockLevelRepository, never()).save(any());
        verify(countRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("v1s-14-13 — name taken → PRODUCT_NAME_ALREADY_EXISTS before any count, guard not called")
    void execute_nameTakenAtCap_dedupWins() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(inProgressSession));
        when(productQuery.existsByName(TenantContext.getCurrentTenant(), "Pagne")).thenReturn(true);

        var cmd = new QuickAddProductCommand(SESSION_ID, "Pagne", CATEGORY_ID, 1, null, ACTOR_ID);
        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS.name()));

        verify(transactionLock).lock("product-limit:" + TENANT);
        verifyNoInteractions(planLimitGuard, productCountPort);
        verify(productCommand, never()).register(any(), any());
    }

    @Test
    @DisplayName("v1s-14-13 — session not found: lock taken, no plan check")
    void execute_sessionNotFound_noPlanCheck() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        var cmd = new QuickAddProductCommand(SESSION_ID, "Pagne", CATEGORY_ID, 1, null, ACTOR_ID);
        assertThatThrownBy(() -> service.execute(cmd)).isInstanceOf(DomainException.class);

        verify(transactionLock).lock("product-limit:" + TENANT);
        verifyNoInteractions(planLimitGuard, productCountPort, subscriptionRepository);
    }
}
