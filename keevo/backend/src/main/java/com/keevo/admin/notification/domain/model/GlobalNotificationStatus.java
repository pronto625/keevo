package com.keevo.admin.notification.domain.model;

/** Lifecycle of a global push: SCHEDULED → SENDING → SENT | FAILED, or SCHEDULED → CANCELLED. */
public enum GlobalNotificationStatus {
    SCHEDULED, SENDING, SENT, FAILED, CANCELLED
}
