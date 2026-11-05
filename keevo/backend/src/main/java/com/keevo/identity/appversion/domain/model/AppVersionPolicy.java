package com.keevo.identity.appversion.domain.model;

import com.keevo.shared.domain.AppVersion;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 19.10 — the version policy of one app identity on one platform ({@code public.app_versions} row): minimum and
 * latest versions (each optional), store link and message. {@link #evaluate} is the single rule (Specification over
 * {@link AppVersion} comparisons): {@code updateRequired} when the sent version is below the minimum,
 * {@code updateAvailable} when it is below the latest version or required. Pure Java.
 */
public record AppVersionPolicy(String appIdentity, AppPlatform platform, String minVersion, String latestVersion,
                               String storeUrl, String message, Instant updatedAt, UUID updatedBy) {

    /** The app identity sent by nobody: the Bref React Native app already in stores sends no identity. */
    public static final String DEFAULT_IDENTITY = "bref";

    /** Kebab-case identity, same rule as the {@code app_versions_app_identity_check} constraint. */
    private static final Pattern IDENTITY = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    public static final int MAX_IDENTITY = 30;

    /** Whether {@code identity} is a valid app identity (kebab-case, at most 30 characters). */
    public static boolean validIdentity(String identity) {
        return identity != null && identity.length() <= MAX_IDENTITY && IDENTITY.matcher(identity).matches();
    }

    /**
     * The answer for the app at {@code sent} ({@code sentText}: the version as the app sent it). A stored version
     * that does not parse (edited by hand) is ignored, like an absent one, and never returned. {@code latestVersion}
     * is the stored latest version, else the minimum when an update is required (the Bref RN app compares it first:
     * it must be above the sent version for the forced screen to show), else the sent version.
     */
    public VersionCheck evaluate(AppVersion sent, String sentText) {
        Optional<AppVersion> min = AppVersion.tryParse(minVersion);
        Optional<AppVersion> latest = AppVersion.tryParse(latestVersion);
        boolean required = min.map(sent::isBefore).orElse(false);
        boolean available = required || latest.map(sent::isBefore).orElse(false);
        String latestText = latest.isPresent() ? latestVersion.strip()
                : required ? minVersion.strip()
                : sentText;
        return new VersionCheck(required, available, storeUrl, message, latestText);
    }
}
