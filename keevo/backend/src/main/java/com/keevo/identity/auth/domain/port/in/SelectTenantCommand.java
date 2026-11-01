package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.DeviceInfo;

/**
 * SelectTenantCommand — Input for step 2 of two-step login (Story 1.7).
 *
 * @param loginToken  the short-lived RS256 JWT from step 1 (scope: "login_pending")
 * @param tenantCode  the tenant the user wants to log into (e.g., "KV-ABC123")
 * @param device      Story 16.11 — the app's device id and readable label (optional)
 */
public record SelectTenantCommand(
        String loginToken,
        String tenantCode,
        DeviceInfo device
) {
    /** Without device data. */
    public SelectTenantCommand(String loginToken, String tenantCode) {
        this(loginToken, tenantCode, DeviceInfo.NONE);
    }
}
