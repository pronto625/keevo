package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;

import java.util.Comparator;
import java.util.List;

/**
 * ByMarginXafDescStrategy — sort by grossMarginXaf descending.
 * Story 7.4, Task 5.3.
 */
public class ByMarginXafDescStrategy implements ProfitabilitySortStrategy {

    @Override
    public List<ProductProfitabilityEntry> sort(List<ProductProfitabilityEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparingLong(ProductProfitabilityEntry::grossMarginXaf).reversed())
                .toList();
    }
}
