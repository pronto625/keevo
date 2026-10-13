package com.keevo.admin.notification.domain.port.in;

/** Story 9-5 — number of devices a target currently reaches (raw params validated → 422). */
public interface EstimateGlobalNotificationUseCase {
    long estimate(String target, String targetTenantId);
}
