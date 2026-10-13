package com.keevo.admin.revenue.domain.port.in;

import com.keevo.admin.revenue.domain.model.RevenueSummary;

public interface GetRevenueSummaryUseCase {
    RevenueSummary getSummary(RevenueRangeQuery query);
}
