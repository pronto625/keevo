package com.keevo.admin.alerts.domain.port.in;

import com.keevo.admin.alerts.domain.model.AlertSeverity;

/** CountAdminAlertsUseCase — unhandled alerts count of the sidebar badge (Story 9-5). */
public interface CountAdminAlertsUseCase {

    /** @param severity null counts every severity */
    long countUnhandled(AlertSeverity severity);
}
