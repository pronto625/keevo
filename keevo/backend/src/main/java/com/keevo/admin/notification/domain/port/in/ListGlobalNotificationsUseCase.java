package com.keevo.admin.notification.domain.port.in;

import com.keevo.admin.notification.domain.model.GlobalNotification;

import java.util.List;

/** Story 9-5 — history of the global pushes of the last 90 days, newest first. */
public interface ListGlobalNotificationsUseCase {
    List<GlobalNotification> history();
}
