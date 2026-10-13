package com.keevo.admin.notification.domain.port.in;

/** Story 9-5 — sends the due SCHEDULED global pushes (scheduler, every minute). */
public interface DispatchGlobalNotificationsUseCase {
    /** @return number of announcements processed (SENT or FAILED) */
    int dispatchDue();
}
