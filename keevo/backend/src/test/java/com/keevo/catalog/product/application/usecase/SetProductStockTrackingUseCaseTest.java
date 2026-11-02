package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.api.StockTrackingGuard;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.product.ProductStockTrackingChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.*;

/** Story 17.12 — the single tracking path: owner, inventory gate, transfer guard, conditional update, one audit. */
@ExtendWith(MockitoExtension.class)
class SetProductStockTrackingUseCaseTest {

    private static final String TENANT = "kv_test01";
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock private ProductRepository productRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EntitlementService entitlements;
    @Mock private ObjectProvider<StockTrackingGuard> guardProvider;
    @Mock private StockTrackingGuard guard;

    private SetProductStockTrackingUseCase useCase;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        useCase = new SetProductStockTrackingUseCase(productRepository, eventPublisher, entitlements, guardProvider);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Product product(boolean tracked) {
        return new Product(PRODUCT, "Huile", null, "KEV-HUIL01", null, 500, 0, 0, null, false,
                ProductStatus.ACTIVE, 0, tracked, Instant.now(), Instant.now());
    }

    private void inventory(ModuleState state) {
        when(entitlements.state(TENANT, "inventory")).thenReturn(state);
    }

    @Test
    void change_isConditional_auditedOnce() {
        inventory(ModuleState.ENABLED);
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(productRepository.findById(PRODUCT)).thenReturn(Optional.of(product(true)), Optional.of(product(false)));
        when(productRepository.updateTrackStock(eq(PRODUCT), eq(false), any())).thenReturn(true);

        var result = useCase.execute(PRODUCT, false, ACTOR, "OWNER");

        assertThat(result.changed()).isTrue();
        assertThat(result.product().isTrackStock()).isFalse();
        verify(guard).checkCanDisable(TENANT, PRODUCT);
        verify(eventPublisher).publishEvent(isA(ProductStockTrackingChangedEvent.class));
    }

    @Test
    void conditionalUpdateLost_noAudit() {
        inventory(ModuleState.ENABLED);
        when(productRepository.findById(PRODUCT)).thenReturn(Optional.of(product(false)));
        when(productRepository.updateTrackStock(eq(PRODUCT), eq(true), any())).thenReturn(false);

        var result = useCase.execute(PRODUCT, true, ACTOR, "OWNER");

        assertThat(result.changed()).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void readOnlyGrace_isAllowed_disabledIsRefused() {
        when(productRepository.findById(PRODUCT)).thenReturn(Optional.of(product(false)));
        inventory(ModuleState.READ_ONLY);
        when(productRepository.updateTrackStock(eq(PRODUCT), eq(true), any())).thenReturn(true);
        assertThat(useCase.execute(PRODUCT, true, ACTOR, "OWNER").changed()).isTrue();

        reset(entitlements);
        inventory(ModuleState.DISABLED);
        assertThatThrownBy(() -> useCase.execute(PRODUCT, true, ACTOR, "OWNER"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.MODULE_NOT_ENTITLED));
        verify(productRepository, times(1)).updateTrackStock(any(), anyBoolean(), any());
    }

    @Test
    void employee_isForbidden() {
        when(productRepository.findById(PRODUCT)).thenReturn(Optional.of(product(true)));

        assertThatThrownBy(() -> useCase.execute(PRODUCT, false, ACTOR, "EMPLOYEE"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(productRepository, never()).updateTrackStock(any(), anyBoolean(), any());
    }

    @Test
    void disableWithTransferInTransit_isRefused_nothingWritten() {
        inventory(ModuleState.ENABLED);
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(productRepository.findById(PRODUCT)).thenReturn(Optional.of(product(true)));
        doThrow(new DomainException(ErrorCode.TRANSFER_IN_TRANSIT, "en cours"))
                .when(guard).checkCanDisable(TENANT, PRODUCT);

        assertThatThrownBy(() -> useCase.execute(PRODUCT, false, ACTOR, "OWNER"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.TRANSFER_IN_TRANSIT));
        verify(productRepository, never()).updateTrackStock(any(), anyBoolean(), any());
    }

    @Test
    void unknownProduct_isNotFound() {
        when(productRepository.findById(PRODUCT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(PRODUCT, true, ACTOR, "OWNER"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
