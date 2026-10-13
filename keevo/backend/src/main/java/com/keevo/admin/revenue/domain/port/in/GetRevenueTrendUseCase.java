package com.keevo.admin.revenue.domain.port.in;

import com.keevo.admin.revenue.domain.model.RevenueTrendPoint;

import java.util.List;

public interface GetRevenueTrendUseCase {
    /** The last 12 calendar months, oldest first, ending with the current one. */
    List<RevenueTrendPoint> getTrend();
}
