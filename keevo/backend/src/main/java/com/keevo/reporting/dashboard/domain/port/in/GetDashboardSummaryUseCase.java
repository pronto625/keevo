package com.keevo.reporting.dashboard.domain.port.in;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;

/**
 * GetDashboardSummaryUseCase — input port for dashboard data retrieval.
 * Story 7.1, Task 2.
 */
public interface GetDashboardSummaryUseCase {

    DashboardSummary execute(GetDashboardSummaryQuery query);

    record GetDashboardSummaryQuery(String tenantId) {}
}
