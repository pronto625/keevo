package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;

import java.util.Comparator;
import java.util.List;

/**
 * ByUnitsDescStrategy — sort by unitsSold descending.
 * Story 7.4, Task 5.5.
 */
public class ByUnitsDescStrategy implements ProfitabilitySortStrategy {

    @Override
    public List<ProductProfitabilityEntry> sort(List<ProductProfitabilityEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparingInt(ProductProfitabilityEntry::unitsSold).reversed())
                .toList();
    }
}
