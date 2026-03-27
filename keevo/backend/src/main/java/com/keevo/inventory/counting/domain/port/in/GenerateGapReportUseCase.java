package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventoryGapReport;

/**
 * Use case for generating a gap analysis report from inventory session data.
 * Story 6.3.
 */
public interface GenerateGapReportUseCase {
    InventoryGapReport execute(GenerateGapReportQuery query);
}
