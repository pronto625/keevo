package com.keevo.identity.appversion.domain.port.in;

import com.keevo.identity.appversion.domain.model.VersionCheck;

/** Story 19.10 — public version check of an app build (no authentication). */
public interface CheckAppVersionUseCase {

    /**
     * @param identity app identity (kebab-case); null or blank = {@code bref} (the Bref app in stores sends none)
     * @param platform {@code ios}, {@code android} or {@code web}, case-insensitive
     * @param version  the app version, {@code MAJOR[.MINOR[.PATCH]]} with optional {@code -pre} / {@code +build}
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_ERROR (422) with a French message
     */
    VersionCheck check(String identity, String platform, String version);
}
