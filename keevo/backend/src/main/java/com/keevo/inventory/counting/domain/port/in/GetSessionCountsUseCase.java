package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventoryCount;

import java.util.List;

public interface GetSessionCountsUseCase {
    List<InventoryCount> execute(GetSessionCountsQuery query);
}
