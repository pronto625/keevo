package com.keevo.identity.auth.domain.model;

/**
 * Story 16.11 — device sent by the app at login, tenant selection and refresh: its id (the sync
 * device id) and a readable label (model and system, e.g. « Redmi Note 12 · Android 14 »).
 * Both optional; sanitised (trimmed, control characters removed, bounded length).
 */
public record DeviceInfo(String deviceId, String deviceLabel) {

    public static final DeviceInfo NONE = new DeviceInfo(null, null);
    static final int MAX_ID = 128;
    static final int MAX_LABEL = 120;

    public DeviceInfo {
        deviceId = clean(deviceId, MAX_ID);
        deviceLabel = clean(deviceLabel, MAX_LABEL);
    }

    public static DeviceInfo of(String deviceId, String deviceLabel) {
        return new DeviceInfo(deviceId, deviceLabel);
    }

    public boolean isEmpty() {
        return deviceId == null && deviceLabel == null;
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim().replaceAll("\\s{2,}", " ");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }
}
