package com.keevo.admin.dashboard.domain.port.in;

import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityItem;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.GrowthPoint;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.Overview;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.TopAccount;

import java.util.List;

/**
 * Story 20.13 — the super-admin platform dashboard (Bref {@code GET /dashboard/overview}, {@code /growth},
 * {@code /top-shops}, {@code /activity}). Raw request values; the service validates them (422 VALIDATION_ERROR, French message).
 */
public interface PlatformDashboardUseCase {

    Overview getOverview();

    List<GrowthPoint> getGrowth(String months);

    List<TopAccount> getTopAccounts(String limit, String days);

    List<ActivityItem> getActivity(String limit);
}
