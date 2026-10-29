package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

/** Story 12.2b — a CSV-imported product's initial stock is a STOCK_ENTRY on the simple product. */
@ExtendWith(MockitoExtension.class)
class CatalogInitialStockAdapterTest {

    @Mock private StockCommandPort stockCommandPort;

    @Test
    void recordInitialStock_isAnOnlineStockEntryWithoutVariant() {
        UUID product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        new CatalogInitialStockAdapter(stockCommandPort)
                .recordInitialStock(product, store, 5, actor, "Import CSV ligne 3");

        verify(stockCommandPort).recordMovement(new StockMovementInput(
                product, null, store, StockMovementKind.STOCK_ENTRY, 5, actor, "Import CSV ligne 3", null, false));
    }
}
