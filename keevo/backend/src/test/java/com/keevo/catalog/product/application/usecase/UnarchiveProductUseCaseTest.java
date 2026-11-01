package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.product.ProductUnarchivedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.api.ProductCountPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Story 17.10 — restoring an archived active product passes the product limit (archived ones are not counted). */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnarchiveProductUseCase")
class UnarchiveProductUseCaseTest {

    @Mock ProductRepository productRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ProductCountPort productCountPort;
    @Mock TenantPlanQuery tenantPlanQuery;
    @Mock PlanLimitCheck planLimit;
    @Mock TransactionLockPort transactionLock;

    UnarchiveProductUseCase useCase;
    final UUID id = UUID.randomUUID();
    final PlanView free = new PlanView(PlanType.FREE, true, null, 1, 500, 2);

    @BeforeEach
    void setUp() {
        useCase = new UnarchiveProductUseCase(productRepository, eventPublisher, productCountPort, tenantPlanQuery,
                planLimit, transactionLock);
        TenantContext.setCurrentTenant("kv_test01");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Product product(boolean archived, ProductStatus status) {
        return new Product(id, "Savon", null, "KEV-ABC123", null, 500, null, null, 0, archived, status,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("archived ACTIVE product: lock, limit check, then restore and event")
    void archivedActive_checksLimit() {
        when(productRepository.findById(id)).thenReturn(Optional.of(product(true, ProductStatus.ACTIVE)));
        when(tenantPlanQuery.findCurrentPlan("kv_test01")).thenReturn(Optional.of(free));

        useCase.execute(new UnarchiveProductUseCase.UnarchiveProductDto(id, UUID.randomUUID()));

        InOrder order = inOrder(transactionLock, planLimit, productRepository);
        order.verify(transactionLock).lock("product-limit:kv_test01");
        order.verify(planLimit).checkProductLimit(eq(free), any());
        order.verify(productRepository).unarchive(id);
        verify(eventPublisher).publishEvent(any(ProductUnarchivedEvent.class));
    }

    @Test
    @DisplayName("limit reached → PLAN_LIMIT_EXCEEDED, product stays archived, no event")
    void limitReached_refused() {
        when(productRepository.findById(id)).thenReturn(Optional.of(product(true, ProductStatus.ACTIVE)));
        when(tenantPlanQuery.findCurrentPlan("kv_test01")).thenReturn(Optional.of(free));
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "products limit reached"))
                .when(planLimit).checkProductLimit(eq(free), any());

        assertThatThrownBy(() -> useCase.execute(new UnarchiveProductUseCase.UnarchiveProductDto(id, UUID.randomUUID())))
                .isInstanceOf(DomainException.class);

        verify(productRepository, never()).unarchive(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("draft or non-archived product: no limit check (not counted either way)")
    void notCounted_noCheck() {
        when(productRepository.findById(id)).thenReturn(Optional.of(product(true, ProductStatus.DRAFT)));
        useCase.execute(new UnarchiveProductUseCase.UnarchiveProductDto(id, UUID.randomUUID()));

        when(productRepository.findById(id)).thenReturn(Optional.of(product(false, ProductStatus.ACTIVE)));
        useCase.execute(new UnarchiveProductUseCase.UnarchiveProductDto(id, UUID.randomUUID()));

        verifyNoInteractions(planLimit, tenantPlanQuery);
        verify(productRepository, times(2)).unarchive(id);
    }
}
