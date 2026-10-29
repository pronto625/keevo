package com.keevo.inventory.stock.application.usecase;

import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * SetStockThresholdUseCaseTest — Story 2.3 (catalog reached through its public API since 12.2a).
 */
@ExtendWith(MockitoExtension.class)
class SetStockThresholdUseCaseTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private ProductQueryPort productQuery;
    @Mock private ProductCommandPort productCommand;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @InjectMocks private SetStockThresholdUseCase useCase;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private ProductView product(int threshold) {
        return new ProductView(PRODUCT_ID, "Widget", "d", "KEV-ABCDEF",
            UUID.randomUUID(), 1000, 800, 50, 10,
            false, ProductState.ACTIVE, threshold,
            Instant.now(), Instant.now());
    }

    private void stubChangeEchoingThreshold() {
        when(productCommand.changeMinimumThreshold(any(), eq(PRODUCT_ID), anyInt()))
            .thenAnswer(inv -> product(inv.getArgument(2)));
    }

    @Test
    void should_update_minimum_threshold() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(product(0)));
        stubChangeEchoingThreshold();

        ProductView result = useCase.execute(PRODUCT_ID, 15, ACTOR_ID);

        assertThat(result.minimumThreshold()).isEqualTo(15);
        verify(productCommand).changeMinimumThreshold(TenantContext.getCurrentTenant(), PRODUCT_ID, 15);
    }

    @Test
    void should_accept_zero_threshold() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(product(10)));
        stubChangeEchoingThreshold();

        ProductView result = useCase.execute(PRODUCT_ID, 0, ACTOR_ID);
        assertThat(result.minimumThreshold()).isEqualTo(0);
    }

    @Test
    void should_throw_for_negative_threshold() {
        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID, -1, ACTOR_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");

        verifyNoInteractions(productQuery, productCommand);
    }

    @Test
    void should_throw_product_not_found() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID, 5, ACTOR_ID))
            .isInstanceOf(DomainException.class)
            .satisfies(ex ->
                assertThat(((DomainException) ex).getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
        verifyNoInteractions(productCommand);
    }

    /** J10 — a real threshold change is recorded in the action history. */
    @Test
    void threshold_change_publishes_audit_event() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(product(5)));
        stubChangeEchoingThreshold();

        useCase.execute(PRODUCT_ID, 12, ACTOR_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.keevo.shared.event.catalog.stock.StockThresholdChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().previousThreshold()).isEqualTo(5);
        assertThat(captor.getValue().newThreshold()).isEqualTo(12);
        assertThat(captor.getValue().productName()).isEqualTo("Widget");
        assertThat(captor.getValue().actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    void same_threshold_publishes_nothing() {
        when(productQuery.findById(TenantContext.getCurrentTenant(), PRODUCT_ID)).thenReturn(Optional.of(product(5)));
        stubChangeEchoingThreshold();

        useCase.execute(PRODUCT_ID, 5, ACTOR_ID);

        verifyNoInteractions(eventPublisher);
    }
}
