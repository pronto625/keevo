package com.keevo.admin.analytics.domain.port.in;

import com.keevo.admin.analytics.domain.model.AnalyticsOverview;

public interface GetAnalyticsOverviewUseCase {
    AnalyticsOverview getOverview();
}
