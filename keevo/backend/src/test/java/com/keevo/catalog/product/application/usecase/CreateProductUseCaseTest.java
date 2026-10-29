package com.keevo.catalog.product.application.usecase;

import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.api.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * TDD tests for CreateProductUseCase (Story 14.13 — AC4: plan limit enforcement).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateProductUseCase")
class CreateProductUseCaseTest {

    // 12.2d: the store / identity / subscription API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12d() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private ProductRepository        productRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductCountPort         productCountPort;
    @Mock private SubscriptionRepository   subscriptionRepository;
    @Mock private PlanLimitGuard           planLimitGuard;
    @Mock private TransactionLockPort transactionLock;

    private CreateProductUseCase useCase;
    private final UUID actorId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new CreateProductUseCase(
                productRepository, eventPublisher, productCountPort,
                SubscriptionApiTestSupport.queryPort(subscriptionRepository), SubscriptionApiTestSupport.limitPort(planLimitGuard), transactionLock);
    }

    private CreateProductUseCase.CreateProductDto dto(String name) {
        return new CreateProductUseCase.CreateProductDto(
                name, null, null, categoryId, 5000, 0, 0, 10,
                actorId, 0, null, null, null, null, null);
    }

    private CreateProductUseCase.CreateProductDto dtoWithClientId(String name, UUID clientId) {
        return new CreateProductUseCase.CreateProductDto(
                name, null, null, categoryId, 5000, 0, 0, 10,
                actorId, 0, null, null, null, null, clientId);
    }

    private void stubPlanUnderLimit() {
        var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
        when(sub.getPlanType()).thenReturn(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
        when(productCountPort.countActiveProducts(any())).thenReturn(0);
        lenient().doNothing().when(planLimitGuard).checkProductLimit(any(), anyInt());
    }

    // ── AC4 Tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC4: limite atteinte → PLAN_LIMIT_EXCEEDED, verify(productRepository, never()).save(...)")
    void planLimitReached_throwsPlanLimitExceeded_neverSaves() {
        when(productRepository.existsByName("Produit Bloqué")).thenReturn(false);
        var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
        when(sub.getPlanType()).thenReturn(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
        when(productCountPort.countActiveProducts(any())).thenReturn(500);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite de produits atteinte"))
                .when(planLimitGuard).checkProductLimit(any(), anyInt());

        var ex = assertThrows(DomainException.class, () -> useCase.execute(dto("Produit Bloqué")));
        assertEquals(ErrorCode.PLAN_LIMIT_EXCEEDED.name(), ex.getDomainCode());

        // AC4: save() must NOT be called when limit is exceeded
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC4: sous la limite → création réussie inchangée")
    void underLimit_creationSucceeds() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlanUnderLimit();
            when(productRepository.existsByName("Produit OK")).thenReturn(false);
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Product result = useCase.execute(dto("Produit OK"));

            assertNotNull(result);
            assertEquals("Produit OK", result.getName());
            assertEquals(ProductStatus.ACTIVE, result.getStatus());
            verify(productRepository).save(any());
            verify(planLimitGuard).checkProductLimit(eq(PlanType.FREE), eq(0));
        }
    }

    // ── Offline-first fix: client-generated id (sync push) ─────────────────────

    @Test
    @DisplayName("clientId fourni → le produit est sauvegardé avec cet id, pas un UUID aléatoire")
    void withClientId_savesProductUnderThatId() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            UUID clientId = UUID.randomUUID();
            when(productRepository.findById(clientId)).thenReturn(Optional.empty());
            stubPlanUnderLimit();
            when(productRepository.existsByName("Produit Offline")).thenReturn(false);
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Product result = useCase.execute(dtoWithClientId("Produit Offline", clientId));

            assertEquals(clientId, result.getId());
        }
    }

    @Test
    @DisplayName("clientId déjà existant → retourne le produit existant, ne sauvegarde pas un doublon")
    void withClientId_alreadyExists_returnsExisting_neverSavesDuplicate() {
        UUID clientId = UUID.randomUUID();
        Product existing = new Product(clientId, "Déjà créé", null, "KEV-000001", categoryId,
                5000, 0, 0, 10, false, ProductStatus.ACTIVE, 0, Instant.now(), Instant.now());
        when(productRepository.findById(clientId)).thenReturn(Optional.of(existing));

        Product result = useCase.execute(dtoWithClientId("Déjà créé", clientId));

        assertEquals(existing, result);
        verify(productRepository, never()).save(any());
        verify(productRepository, never()).existsByName(any());
        // Story 13.9: concurrent same-clientId creates are serialised — lock before the lookup.
        InOrder inOrder = inOrder(transactionLock, productRepository);
        inOrder.verify(transactionLock).lock(any());
        inOrder.verify(productRepository).findById(clientId);
    }

    // ── Story 13.9: plan-limit TOCTOU ─────────────────────────────────────────

    @Test
    @DisplayName("13.9: verrou tenant pris en premier (avant nom/comptage), puis save")
    void locksTenantBeforeCount_thenSaves() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlanUnderLimit();
            when(productRepository.existsByName("Produit Concurrent")).thenReturn(false);
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            useCase.execute(dto("Produit Concurrent"));

            InOrder inOrder = inOrder(transactionLock, productCountPort, productRepository);
            inOrder.verify(transactionLock).lock("product-limit:kv_test");
            inOrder.verify(productRepository).existsByName("Produit Concurrent");
            inOrder.verify(productCountPort).countActiveProducts(any());
            inOrder.verify(productRepository).save(any());
        }
    }

    @Test
    @DisplayName("13.9: perdant de la course (count = max après commit du gagnant) → PLAN_LIMIT_EXCEEDED")
    void concurrentLoserAtLimit_planLimitExceeded_neverSaves() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            when(productRepository.existsByName("Produit Perdant")).thenReturn(false);
            var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
            when(sub.getPlanType()).thenReturn(PlanType.FREE);
            when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
            // The winner's product is committed before the lock is released: the loser counts it.
            when(productCountPort.countActiveProducts(any())).thenReturn(20);
            doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite de produits atteinte"))
                    .when(planLimitGuard).checkProductLimit(PlanType.FREE, 20);

            var ex = assertThrows(DomainException.class, () -> useCase.execute(dto("Produit Perdant")));

            assertEquals(ErrorCode.PLAN_LIMIT_EXCEEDED.name(), ex.getDomainCode());
            InOrder inOrder = inOrder(transactionLock, productCountPort);
            inOrder.verify(transactionLock).lock("product-limit:kv_test");
            inOrder.verify(productCountPort).countActiveProducts(any());
            verify(productRepository, never()).save(any());
        }
    }

    // ── v1s-15-9: plan check at the same position as CreateDraftProductUseCase ──

    @Test
    @DisplayName("v1s-15-9: plan limit is rejected before the SKU-uniqueness check")
    void planLimitReached_rejectedBeforeSkuUniquenessCheck() {
        when(productRepository.existsByName("Produit SKU Pris")).thenReturn(false);
        var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
        when(sub.getPlanType()).thenReturn(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
        when(productCountPort.countActiveProducts(any())).thenReturn(500);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite de produits atteinte"))
                .when(planLimitGuard).checkProductLimit(any(), anyInt());
        // An already-taken SKU would raise IllegalArgumentException if it were checked first.
        lenient().when(productRepository.findBySku("KEV-TAKEN1")).thenReturn(Optional.of(mock(Product.class)));

        var withTakenSku = new CreateProductUseCase.CreateProductDto(
                "Produit SKU Pris", null, "KEV-TAKEN1", categoryId, 5000, 0, 0, 10,
                actorId, 0, null, null, null, null, null);

        var ex = assertThrows(DomainException.class, () -> useCase.execute(withTakenSku));
        assertEquals(ErrorCode.PLAN_LIMIT_EXCEEDED.name(), ex.getDomainCode());
        verify(productRepository, never()).findBySku(any());
        verify(productRepository, never()).save(any());
    }
}
