package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

/** Story 12.2b — a CSV-imported product's initial stock is a STOCK_ENTRY on the simple product. */
@ExtendWith(MockitoExtension.class)
class CatalogInitialStockAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private StockCommandPort stockCommandPort;

    @Test
    void recordInitialStock_isAnOnlineStockEntryWithoutVariant() {
        UUID product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        new CatalogInitialStockAdapter(stockCommandPort)
                .recordInitialStock(TenantContext.getCurrentTenant(), product, store, 5, actor, "Import CSV ligne 3");

        verify(stockCommandPort).recordMovement(TenantContext.getCurrentTenant(), new StockMovementInput(
                product, null, store, StockMovementKind.STOCK_ENTRY, 5, actor, "Import CSV ligne 3", null, false));
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CatalogInitialStockAdapter(stockCommandPort).recordInitialStock("kv_other1", java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 5, java.util.UUID.randomUUID(), "n"))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CatalogInitialStockAdapter(stockCommandPort).recordInitialStock(null, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 5, java.util.UUID.randomUUID(), "n"))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(stockCommandPort);
    }
}
