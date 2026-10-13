package com.keevo.admin.notification.domain.port.in;

/** Story 9-5 — deletes the global pushes older than 90 days (daily). */
public interface PurgeGlobalNotificationsUseCase {
    int purgeOld();
}
