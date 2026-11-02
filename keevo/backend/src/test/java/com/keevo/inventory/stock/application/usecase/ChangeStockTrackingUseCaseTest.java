package com.keevo.inventory.stock.application.usecase;

import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.StockTrackingUpdate;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.inventory.api.StockTrackingChange;
import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.inventory.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Story 17.12 — enabling / disabling stock tracking, with the optional starting quantity. */
@ExtendWith(MockitoExtension.class)
class ChangeStockTrackingUseCaseTest {

    private static final String TENANT = "kv_test01";
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID MOVEMENT = UUID.randomUUID();

    @Mock private ProductCommandPort productCommand;
    @Mock private StoreQueryPort storeQuery;
    @Mock private StockOperationService stockOperationService;

    private ChangeStockTrackingUseCase useCase;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        useCase = new ChangeStockTrackingUseCase(productCommand, storeQuery, stockOperationService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ProductView product(boolean tracked) {
        return new ProductView(PRODUCT, "Pagne", null, "KEV-PAGNE1", null, 1000, 0, 0, 0, false,
                ProductState.ACTIVE, 0, Instant.now(), Instant.now(), tracked);
    }

    /** The catalogue's answer: the product after the call; {@code changed} = this call switched it. */
    private static StockTrackingUpdate update(boolean changed) {
        return new StockTrackingUpdate(product(true), changed);
    }

    private void store(boolean active) {
        when(storeQuery.findById(TENANT, STORE)).thenReturn(Optional.of(
                new StoreView(STORE, "Boutique", StoreType.STORE, null, null, active, Instant.now(), Instant.now())));
    }

    private static StockTrackingChange change(boolean tracked, Integer quantity, UUID storeId, String role) {
        return new StockTrackingChange(PRODUCT, tracked, quantity, storeId, MOVEMENT, ACTOR, role);
    }

    @Test
    void enableWithQuantity_setsTrackingThenOneStockEntryWithTheClientId() {
        store(true);
        when(productCommand.setStockTracking(TENANT, PRODUCT, true, ACTOR, "OWNER")).thenReturn(update(true));

        var result = useCase.execute(change(true, 12, STORE, "OWNER"));

        assertThat(result.product().trackStock()).isTrue();
        var order = inOrder(productCommand, stockOperationService);
        order.verify(productCommand).setStockTracking(TENANT, PRODUCT, true, ACTOR, "OWNER");
        order.verify(stockOperationService).recordOperation(PRODUCT, null, STORE, MovementType.STOCK_ENTRY, 12,
                ACTOR, ChangeStockTrackingUseCase.INITIAL_STOCK_NOTE, MOVEMENT, false);
    }

    @Test
    void enableEmptyOrZero_noMovement() {
        when(productCommand.setStockTracking(TENANT, PRODUCT, true, ACTOR, "OWNER")).thenReturn(update(true));

        useCase.execute(change(true, null, null, "OWNER"));
        useCase.execute(change(true, 0, null, "OWNER"));

        verifyNoInteractions(stockOperationService);
    }

    @Test
    void quantityOnAlreadyTrackedProduct_isIgnored_noSecondEntry() {
        // the catalogue's conditional update did not switch anything (already tracked / lost a concurrent race)
        store(true);
        when(productCommand.setStockTracking(TENANT, PRODUCT, true, ACTOR, "OWNER")).thenReturn(update(false));

        var result = useCase.execute(change(true, 12, STORE, "OWNER"));

        assertThat(result.initialEntry()).isNull();
        verifyNoInteractions(stockOperationService);
    }

    @Test
    void disable_keepsLevels_noMovement() {
        when(productCommand.setStockTracking(TENANT, PRODUCT, false, ACTOR, "OWNER")).thenReturn(update(false));

        useCase.execute(change(false, null, null, "OWNER"));

        verifyNoInteractions(stockOperationService);
    }

    @Test
    void employee_isForbidden_beforeAnything() {
        assertThatThrownBy(() -> useCase.execute(change(true, 5, STORE, "EMPLOYEE")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(productCommand, stockOperationService);
    }

    @Test
    void invalidQuantities_areValidationErrors() {
        for (StockTrackingChange bad : new StockTrackingChange[] {
                change(true, -1, STORE, "OWNER"),     // negative
                change(false, 3, STORE, "OWNER"),     // quantity on a disable
                change(true, 3, null, "OWNER") }) {   // quantity without store
            assertThatThrownBy(() -> useCase.execute(bad))
                    .isInstanceOf(DomainException.class)
                    .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                            .isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
        verifyNoInteractions(productCommand, stockOperationService);
    }

    @Test
    void inactiveStore_isRefused() {
        store(false);

        assertThatThrownBy(() -> useCase.execute(change(true, 3, STORE, "OWNER")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.STORE_NOT_ACTIVE));
        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any(),
                any(), anyBoolean());
    }

    @Test
    void unknownStore_withAnAlreadyTrackedProduct_isRefused_nothingChanged() {
        when(storeQuery.findById(TENANT, STORE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(change(true, 3, STORE, "OWNER")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.STORE_NOT_FOUND));
        verifyNoInteractions(productCommand, stockOperationService);
    }
}
