package com.keevo.admin.subscription.domain.port.in;

import com.keevo.admin.subscription.domain.model.PlanAlert;

import java.util.List;

/** ListPlanAlertsUseCase — plans expiring within 7 days and plans expired in the last 7 days (Story 9-2). */
public interface ListPlanAlertsUseCase {

    List<PlanAlert> listAlerts();
}
