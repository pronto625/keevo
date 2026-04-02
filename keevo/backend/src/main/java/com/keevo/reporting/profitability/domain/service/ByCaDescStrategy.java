package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;

import java.util.Comparator;
import java.util.List;

/**
 * ByCaDescStrategy — sort by totalRevenue (CA) descending.
 * Story 7.4, Task 5.4.
 */
public class ByCaDescStrategy implements ProfitabilitySortStrategy {

    @Override
    public List<ProductProfitabilityEntry> sort(List<ProductProfitabilityEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparingLong(ProductProfitabilityEntry::totalRevenue).reversed())
                .toList();
    }
}
