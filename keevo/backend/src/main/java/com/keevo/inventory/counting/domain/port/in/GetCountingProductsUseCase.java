package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventoryProductRow;

import java.util.List;

public interface GetCountingProductsUseCase {
    List<InventoryProductRow> execute(GetCountingProductsQuery query);
}
