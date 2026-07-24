package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Mock private ProductRepository        productRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductCountPort         productCountPort;
    @Mock private SubscriptionRepository   subscriptionRepository;
    @Mock private PlanLimitGuard           planLimitGuard;

    private CreateProductUseCase useCase;
    private final UUID actorId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new CreateProductUseCase(
                productRepository, eventPublisher, productCountPort,
                subscriptionRepository, planLimitGuard);
    }

    private CreateProductUseCase.CreateProductDto dto(String name) {
        return new CreateProductUseCase.CreateProductDto(
                name, null, null, categoryId, 5000, 0, 0, 10,
                actorId, 0, null, null, null, null);
    }

    private void stubPlanUnderLimit() {
        var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
        when(sub.getPlanType()).thenReturn(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
        when(productCountPort.countActiveProducts()).thenReturn(0);
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
        when(productCountPort.countActiveProducts()).thenReturn(500);
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
}
