package com.keevo.shared.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 19.10 — an app version as the apps send it and the super-admin types it: {@code MAJOR[.MINOR[.PATCH]]}, with
 * an optional {@code -pre} and/or {@code +build} suffix that is ignored ({@code 2.2.0+80} = {@code 2.2.0}). Missing
 * parts are 0 ({@code 2} = {@code 2.0} = {@code 2.0.0}); the three numbers are compared numerically
 * ({@code 2.10.0 > 2.9.0}). Anything else (letters, empty parts, negative or over-long numbers) is invalid.
 *
 * <p>The single version rule of app versions (the module catalogue keeps its own strict semver). Pure Java.
 */
public record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion> {

    /**
     * Longest text accepted from an app (sent versions). Stored versions are bounded separately at 20 characters
     * ({@code app_versions.min_version / latest_version} are {@code VARCHAR(20)}).
     */
    public static final int MAX_LENGTH = 50;

    private static final Pattern FORMAT = Pattern.compile(
            "(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:\\+[0-9A-Za-z][0-9A-Za-z.-]*)?");

    private static final Pattern RELEASE = Pattern.compile("\\d{1,9}(?:\\.\\d{1,9}){0,2}");

    public AppVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("negative version part");
        }
    }

    /** The version of {@code text} (surrounding spaces ignored), or empty when it is null or invalid. */
    public static Optional<AppVersion> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.strip();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new AppVersion(Integer.parseInt(m.group(1)), part(m.group(2)), part(m.group(3))));
    }

    /** Whether {@code text} is a valid version. */
    public static boolean isValid(String text) {
        return tryParse(text).isPresent();
    }

    /** Whether {@code text} is a plain release version: {@code MAJOR[.MINOR[.PATCH]]}, no {@code -pre} / {@code +build}. */
    public static boolean isRelease(String text) {
        return text != null && RELEASE.matcher(text.strip()).matches() && isValid(text);
    }

    /** Whether this version is strictly older than {@code other}. */
    public boolean isBefore(AppVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(AppVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        return c != 0 ? c : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    private static int part(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }
}
