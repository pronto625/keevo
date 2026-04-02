package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.SortOption;

/**
 * ProfitabilitySortStrategyFactory — GoF Factory Method.
 * Resolves the correct {@link ProfitabilitySortStrategy} for a given {@link SortOption}.
 * Story 7.4, Task 5.6.
 */
public final class ProfitabilitySortStrategyFactory {

    private ProfitabilitySortStrategyFactory() {}

    public static ProfitabilitySortStrategy of(SortOption option) {
        return switch (option) {
            case MARGIN_XAF_DESC -> new ByMarginXafDescStrategy();
            case CA_DESC         -> new ByCaDescStrategy();
            case UNITS_DESC      -> new ByUnitsDescStrategy();
            default              -> new ByMarginPctDescStrategy();
        };
    }
}
