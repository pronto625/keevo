package com.keevo.catalog.product.application.usecase;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.ProductCreatedProgressivelyEvent;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.event.catalog.product.DraftProductPendingEvent;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.identity.api.PlanType;
import com.keevo.subscription.api.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD tests for CreateDraftProductUseCase (Story 2.4 — AC6, AC7, AC8).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateDraftProductUseCase")
class CreateDraftProductUseCaseTest {

    // 12.2d: the store / identity / subscription API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12d() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private ProductRepository           productRepository;
    @Mock private ProductCountPort            productCountPort;
    @Mock private SubscriptionRepository      subscriptionRepository;
    @Mock private PlanLimitGuard              planLimitGuard;
    @Mock private TransactionLockPort transactionLock;
    @Mock private ApplicationEventPublisher   eventPublisher;

    private CreateDraftProductUseCase useCase;
    private final UUID actorId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new CreateDraftProductUseCase(
                productRepository, productCountPort,
                SubscriptionApiTestSupport.queryPort(subscriptionRepository), SubscriptionApiTestSupport.limitPort(planLimitGuard), eventPublisher, transactionLock);
    }

    private CreateDraftProductUseCase.CreateDraftCommand ownerCmd(String name) {
        return new CreateDraftProductUseCase.CreateDraftCommand(
                name, null, categoryId, 5000, 0, 0, actorId, "OWNER", "Simon");
    }

    private CreateDraftProductUseCase.CreateDraftCommand employeeCmd(String name) {
        return new CreateDraftProductUseCase.CreateDraftCommand(
                name, null, categoryId, 5000, 0, 0, actorId, "EMPLOYEE", "Loïc");
    }

    private Product fakeSaved(String name) {
        return new Product(UUID.randomUUID(), name, null, "KEV-DRAFT1",
                categoryId, 5000, 0, 0, 0, false, ProductStatus.DRAFT, 0,
                Instant.now(), Instant.now());
    }

    private void stubPlan() {
        var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
        when(sub.getPlanType()).thenReturn(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
        when(productCountPort.countActiveProducts(any())).thenReturn(0);
        lenient().doNothing().when(planLimitGuard).checkProductLimit(any(), anyInt());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldCreateProductWithDraftStatus")
    void shouldCreateProductWithDraftStatus() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan();
            when(productRepository.existsByName("Chapeau")).thenReturn(false);
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Product result = useCase.execute(ownerCmd("Chapeau"));

            assertEquals(ProductStatus.DRAFT, result.getStatus());
            verify(productRepository).save(argThat(p -> p.getStatus() == ProductStatus.DRAFT));
        }
    }

    @Test
    @DisplayName("shouldThrowProductNameAlreadyExistsWhenNameExistsAsActive")
    void shouldThrowProductNameAlreadyExistsWhenNameExistsAsActive() {
        when(productRepository.existsByName("Chapeau")).thenReturn(true);

        var ex = assertThrows(DomainException.class, () -> useCase.execute(ownerCmd("Chapeau")));
        assertEquals(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS.name(), ex.getDomainCode());
    }

    @Test
    @DisplayName("shouldThrowProductNameAlreadyExistsWhenNameExistsAsDraft")
    void shouldThrowProductNameAlreadyExistsWhenNameExistsAsDraft() {
        // Same check — existsByName covers both ACTIVE and DRAFT
        when(productRepository.existsByName("Sandales bleues")).thenReturn(true);

        var ex = assertThrows(DomainException.class, () -> useCase.execute(employeeCmd("Sandales bleues")));
        assertEquals(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS.name(), ex.getDomainCode());
    }

    @Test
    @DisplayName("shouldPublishProgressiveCreationEvent")
    void shouldPublishProgressiveCreationEvent() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan();
            when(productRepository.existsByName("Casquette")).thenReturn(false);
            when(productRepository.save(any())).thenReturn(fakeSaved("Casquette"));

            useCase.execute(ownerCmd("Casquette"));

            // 12.6a: the pending-draft record first (messaging, synchronous), then the progressive-creation event
            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(cap.capture());
            assertInstanceOf(DraftProductPendingEvent.class, cap.getAllValues().get(0));
            var pending = (DraftProductPendingEvent) cap.getAllValues().get(0);
            assertEquals("Casquette", pending.productName());
            assertEquals("kv_test", pending.tenantId());
            assertNotNull(pending.productId());
            assertInstanceOf(ProductCreatedProgressivelyEvent.class, cap.getAllValues().get(1));
            var event = (ProductCreatedProgressivelyEvent) cap.getAllValues().get(1);
            assertEquals("DRAFT", event.initialStatus());
        }
    }

    @Test
    @DisplayName("shouldAllowOwnerToCreateDraft")
    void shouldAllowOwnerToCreateDraft() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan();
            when(productRepository.existsByName("Article A")).thenReturn(false);
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertDoesNotThrow(() -> useCase.execute(ownerCmd("Article A")));
        }
    }

    @Test
    @DisplayName("shouldAllowEmployeeToCreateDraft")
    void shouldAllowEmployeeToCreateDraft() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan(); // plan limit applies to all roles
            when(productRepository.existsByName("Article B")).thenReturn(false);
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertDoesNotThrow(() -> useCase.execute(employeeCmd("Article B")));
        }
    }

    @Test
    @DisplayName("shouldEnforcePlanLimitForEmployeeToo")
    void shouldEnforcePlanLimitForEmployeeToo() {
        when(productRepository.existsByName("Article C")).thenReturn(false);
        var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
        when(sub.getPlanType()).thenReturn(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
        when(productCountPort.countActiveProducts(any())).thenReturn(500);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite atteinte"))
                .when(planLimitGuard).checkProductLimit(any(), anyInt());

        var ex = assertThrows(DomainException.class, () -> useCase.execute(employeeCmd("Article C")));
        assertEquals(ErrorCode.PLAN_LIMIT_EXCEEDED.name(), ex.getDomainCode());
    }

    // ── Story 13.9: plan-limit TOCTOU ─────────────────────────────────────────

    @Test
    @DisplayName("13.9: verrou tenant (clé partagée avec CreateProduct) pris en premier")
    void locksTenantBeforeCount_thenSaves() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan();
            when(productRepository.existsByName("Article Lock")).thenReturn(false);
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            useCase.execute(ownerCmd("Article Lock"));

            InOrder inOrder = inOrder(transactionLock, productCountPort, productRepository);
            inOrder.verify(transactionLock).lock("product-limit:kv_test");
            inOrder.verify(productRepository).existsByName("Article Lock");
            inOrder.verify(productCountPort).countActiveProducts(any());
            inOrder.verify(productRepository).save(any());
        }
    }

    @Test
    @DisplayName("13.9: perdant de la course à la limite → PLAN_LIMIT_EXCEEDED, pas de save")
    void concurrentLoserAtLimit_planLimitExceeded_neverSaves() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            when(productRepository.existsByName("Article Perdant")).thenReturn(false);
            var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
            when(sub.getPlanType()).thenReturn(PlanType.FREE);
            when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
            when(productCountPort.countActiveProducts(any())).thenReturn(20);
            doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite atteinte"))
                    .when(planLimitGuard).checkProductLimit(PlanType.FREE, 20);

            var ex = assertThrows(DomainException.class, () -> useCase.execute(ownerCmd("Article Perdant")));

            assertEquals(ErrorCode.PLAN_LIMIT_EXCEEDED.name(), ex.getDomainCode());
            verify(transactionLock).lock("product-limit:kv_test");
            verify(productRepository, never()).save(any());
        }
    }
    @Test
    @DisplayName("v1s-13-9 follow-up: with a clientId, the tenant lock precedes the idempotency lookup")
    void withClientId_locksBeforeIdempotencyLookup() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            UUID clientId = UUID.randomUUID();
            Product existing = fakeSaved("Chapeau");
            when(productRepository.findById(clientId)).thenReturn(Optional.of(existing));

            Product result = useCase.execute(new CreateDraftProductUseCase.CreateDraftCommand(
                    clientId, "Chapeau", null, categoryId, 5000, 0, 0, actorId, "EMPLOYEE", "Loïc"));

            assertEquals(existing, result);
            org.mockito.InOrder inOrder = inOrder(transactionLock, productRepository);
            inOrder.verify(transactionLock).lock("product-limit:kv_test");
            inOrder.verify(productRepository).findById(clientId);
            verify(productRepository, never()).existsByName(any());
            verify(productRepository, never()).save(any());
        }
    }
}
