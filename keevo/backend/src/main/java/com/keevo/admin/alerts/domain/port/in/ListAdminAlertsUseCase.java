package com.keevo.admin.alerts.domain.port.in;

import com.keevo.admin.alerts.domain.model.AdminAlert;

import java.util.List;

/** ListAdminAlertsUseCase — platform alerts feed, newest first (Story 9-5). */
public interface ListAdminAlertsUseCase {

    List<AdminAlert> listAlerts(boolean includeHandled);
}
