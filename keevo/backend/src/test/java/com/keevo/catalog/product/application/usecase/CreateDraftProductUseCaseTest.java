package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.ProductCreatedProgressivelyEvent;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock private ProductRepository           productRepository;
    @Mock private DraftNotificationRepository draftRepository;
    @Mock private ProductCountPort            productCountPort;
    @Mock private SubscriptionRepository      subscriptionRepository;
    @Mock private PlanLimitGuard              planLimitGuard;
    @Mock private ApplicationEventPublisher   eventPublisher;

    private CreateDraftProductUseCase useCase;
    private final UUID actorId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new CreateDraftProductUseCase(
                productRepository, draftRepository, productCountPort,
                subscriptionRepository, planLimitGuard, eventPublisher);
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
        when(productCountPort.countActiveProducts()).thenReturn(0);
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
            doNothing().when(draftRepository).save(any());

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
            doNothing().when(draftRepository).save(any());

            useCase.execute(ownerCmd("Casquette"));

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(cap.capture());
            assertInstanceOf(ProductCreatedProgressivelyEvent.class, cap.getValue());
            var event = (ProductCreatedProgressivelyEvent) cap.getValue();
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
            doNothing().when(draftRepository).save(any());

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
            doNothing().when(draftRepository).save(any());

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
        when(productCountPort.countActiveProducts()).thenReturn(500);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite atteinte"))
                .when(planLimitGuard).checkProductLimit(any(), anyInt());

        var ex = assertThrows(DomainException.class, () -> useCase.execute(employeeCmd("Article C")));
        assertEquals(ErrorCode.PLAN_LIMIT_EXCEEDED.name(), ex.getDomainCode());
    }
}
