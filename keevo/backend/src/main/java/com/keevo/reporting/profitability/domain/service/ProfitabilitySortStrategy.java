package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;

import java.util.List;

/**
 * ProfitabilitySortStrategy — GoF Strategy interface.
 * Story 7.4, Task 5.1.
 */
@FunctionalInterface
public interface ProfitabilitySortStrategy {
    List<ProductProfitabilityEntry> sort(List<ProductProfitabilityEntry> entries);
}
