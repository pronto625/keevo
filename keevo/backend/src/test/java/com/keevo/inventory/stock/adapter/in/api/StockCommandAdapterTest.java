package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.stock.application.usecase.AdjustStockUseCase;
import com.keevo.inventory.stock.application.usecase.RecordStockEntryUseCase;
import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.inventory.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Story 12.2b — the stock command port delegates to the existing stock service and use cases. */
@ExtendWith(MockitoExtension.class)
class StockCommandAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private StockOperationService stockOperationService;
    @Mock private AdjustStockUseCase adjustStockUseCase;
    @Mock private RecordStockEntryUseCase recordStockEntryUseCase;

    private StockCommandAdapter adapter;

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID MOVEMENT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new StockCommandAdapter(stockOperationService, adjustStockUseCase, recordStockEntryUseCase);
    }

    @Test
    void recordMovement_passesEveryFieldToTheStockService() {
        adapter.recordMovement(TenantContext.getCurrentTenant(), new StockMovementInput(PRODUCT, VARIANT, STORE, StockMovementKind.SALE,
                -2, ACTOR, "Vente 1", MOVEMENT, true));

        verify(stockOperationService).recordOperation(PRODUCT, VARIANT, STORE, MovementType.SALE,
                -2, ACTOR, "Vente 1", MOVEMENT, true);
    }

    @Test
    void recordMovement_onlineConstructor_serverIdAndNegativeStockRefused() {
        adapter.recordMovement(TenantContext.getCurrentTenant(), new StockMovementInput(PRODUCT, null, STORE, StockMovementKind.STOCK_ENTRY,
                5, ACTOR, "Import CSV ligne 2"));

        verify(stockOperationService).recordOperation(PRODUCT, null, STORE, MovementType.STOCK_ENTRY,
                5, ACTOR, "Import CSV ligne 2", null, false);
    }

    @ParameterizedTest
    @EnumSource(StockMovementKind.class)
    void everyKind_mapsToTheMovementTypeOfTheSameName(StockMovementKind kind) {
        assertThat(StockCommandAdapter.toMovementType(kind).name()).isEqualTo(kind.name());
    }

    @Test
    void recordMovement_insufficientStock_propagatesTheDomainError() {
        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK, "Stock insuffisant"))
                .when(stockOperationService).recordOperation(any(), any(), any(), any(), anyInt(),
                        any(), any(), any(), anyBoolean());

        assertThatThrownBy(() -> adapter.recordMovement(TenantContext.getCurrentTenant(), new StockMovementInput(PRODUCT, null, STORE,
                StockMovementKind.SALE, -30, ACTOR, "Vente")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDomainCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK.name()));
    }

    @Test
    void adjustTo_delegatesToTheAdjustUseCase() {
        adapter.adjustTo(TenantContext.getCurrentTenant(), PRODUCT, VARIANT, STORE, 12, ACTOR, "Casse", MOVEMENT);

        verify(adjustStockUseCase).execute(PRODUCT, VARIANT, STORE, 12, ACTOR, "Casse", MOVEMENT);
        verifyNoInteractions(stockOperationService, recordStockEntryUseCase);
    }

    @Test
    void recordEntry_delegatesToTheEntryUseCase() {
        adapter.recordEntry(TenantContext.getCurrentTenant(), PRODUCT, null, STORE, 7, ACTOR, "Livraison", null);

        verify(recordStockEntryUseCase).execute(PRODUCT, null, STORE, 7, ACTOR, "Livraison", null);
        verifyNoInteractions(stockOperationService, adjustStockUseCase);
    }

    @Test
    void stockMovementInput_nullKind_isAValidationError() {
        assertThatThrownBy(() -> new StockMovementInput(PRODUCT, null, STORE, null, -1, ACTOR, "Vente"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDomainCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.name()));
        verifyNoInteractions(stockOperationService);
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.adjustTo("kv_other1", java.util.UUID.randomUUID(), null, java.util.UUID.randomUUID(), 3, java.util.UUID.randomUUID(), null, null))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.adjustTo(null, java.util.UUID.randomUUID(), null, java.util.UUID.randomUUID(), 3, java.util.UUID.randomUUID(), null, null))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(stockOperationService, adjustStockUseCase, recordStockEntryUseCase);
    }
}
