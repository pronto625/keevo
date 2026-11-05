package com.keevo.identity.appversion.domain.model;

/**
 * Story 19.10 — what an app learns about its version: {@code updateRequired} (below the minimum: the app must update
 * before going on), {@code updateAvailable} (below the latest version, or required), the store link, the message and
 * the latest version (the sent version when none is recorded).
 */
public record VersionCheck(boolean updateRequired, boolean updateAvailable, String storeUrl, String message,
                           String latestVersion) {

    /** Nothing recorded for this app and platform: nothing required, nothing offered. */
    public static VersionCheck nothingRecorded(String sentVersion) {
        return new VersionCheck(false, false, null, null, sentVersion);
    }
}
