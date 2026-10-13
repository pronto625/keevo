package com.keevo.admin.notification.domain.model;

/** Why a global push ended FAILED (Story 9-5); labelled in French by the dashboard. */
public enum GlobalNotificationFailureReason {
    /** FCM not configured / not initialised. */
    PUSH_UNAVAILABLE,
    /** Every multicast chunk sent to Firebase threw. */
    ALL_CHUNKS_FAILED,
    /** Crash / redeploy / database outage during the send (or SENDING for more than 15 minutes). */
    INTERRUPTED,
    /** No device token in the targeted companies at send time. */
    NO_RECIPIENT
}
