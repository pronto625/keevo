package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;

import java.util.Comparator;
import java.util.List;

/**
 * ByMarginPctDescStrategy — sort by marginPercent descending; LOSS entries last.
 * Story 7.4, Task 5.2.
 */
public class ByMarginPctDescStrategy implements ProfitabilitySortStrategy {

    @Override
    public List<ProductProfitabilityEntry> sort(List<ProductProfitabilityEntry> entries) {
        return entries.stream()
                .sorted(Comparator
                        // non-loss entries first (0=non-loss, 1=loss)
                        .<ProductProfitabilityEntry, Integer>comparing(e -> e.isLoss() ? 1 : 0)
                        // then descending margin percent (negate for desc)
                        .thenComparingDouble(e -> -e.marginPercent())
                )
                .toList();
    }
}
