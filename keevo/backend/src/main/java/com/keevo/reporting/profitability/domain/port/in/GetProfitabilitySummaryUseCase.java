package com.keevo.reporting.profitability.domain.port.in;

import com.keevo.reporting.profitability.domain.model.ProfitabilitySummary;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase.ProfitabilityQuery;

/**
 * Story 20.9 — inbound port of the profitability summary: revenue of the period and the part whose margin is unknown
 * (lines without product).
 */
public interface GetProfitabilitySummaryUseCase {

    /** The summary of {@code query}'s period and store (sort ignored). */
    ProfitabilitySummary getSummary(ProfitabilityQuery query);
}
