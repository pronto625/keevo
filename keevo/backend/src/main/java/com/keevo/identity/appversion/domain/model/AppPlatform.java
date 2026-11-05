package com.keevo.identity.appversion.domain.model;

import java.util.Locale;

/**
 * Story 19.10 — the platform of an app build: {@code ios}, {@code android}, {@code web} (the stored code, also the
 * {@code app_versions.platform} check constraint). Pure Java.
 */
public enum AppPlatform {
    IOS,
    ANDROID,
    WEB;

    /** The stored code: {@code ios}, {@code android}, {@code web}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The platform of a code, case-insensitive (spaces ignored), or {@code null} for null / unknown text. */
    public static AppPlatform parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip().toLowerCase(Locale.ROOT);
        for (AppPlatform p : values()) {
            if (p.code().equals(value)) {
                return p;
            }
        }
        return null;
    }
}
