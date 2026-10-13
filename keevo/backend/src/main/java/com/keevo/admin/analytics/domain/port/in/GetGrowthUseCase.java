package com.keevo.admin.analytics.domain.port.in;

import com.keevo.admin.analytics.domain.model.GrowthPoint;

import java.util.List;

public interface GetGrowthUseCase {
    /** @param months number of calendar months ending with the current one (null/blank → 12, 1..24) */
    List<GrowthPoint> getGrowth(String months);
}
