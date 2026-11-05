package com.keevo.identity.appversion.application.service;

import com.keevo.identity.appversion.domain.model.AppPlatform;
import com.keevo.identity.appversion.domain.model.AppVersionPolicy;
import com.keevo.identity.appversion.domain.model.VersionCheck;
import com.keevo.identity.appversion.domain.port.in.CheckAppVersionUseCase;
import com.keevo.identity.appversion.domain.port.out.AppVersionPolicyStore;
import com.keevo.shared.domain.AppVersion;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Story 19.10 — public version check: a stateless query, one primary-key read of {@code public.app_versions} (O(1)),
 * then {@link AppVersionPolicy#evaluate}. No row = nothing required, nothing offered.
 */
@Service
public class AppVersionCheckService implements CheckAppVersionUseCase {

    static final String INVALID_IDENTITY = "Application inconnue : identifiant invalide";
    static final String INVALID_PLATFORM = "Plateforme inconnue : indiquez ios, android ou web";
    static final String MISSING_VERSION = "Indiquez la version de l'application";
    static final String INVALID_VERSION = "Version de l'application invalide (exemple : 2.1.0)";

    private final AppVersionPolicyStore store;

    public AppVersionCheckService(AppVersionPolicyStore store) {
        this.store = store;
    }

    @Override
    public VersionCheck check(String identity, String platform, String version) {
        String appIdentity = identity == null || identity.isBlank()
                ? AppVersionPolicy.DEFAULT_IDENTITY : identity.strip().toLowerCase(Locale.ROOT);
        if (!AppVersionPolicy.validIdentity(appIdentity)) {
            throw invalid(INVALID_IDENTITY);
        }
        AppPlatform appPlatform = AppPlatform.parse(platform);
        if (appPlatform == null) {
            throw invalid(INVALID_PLATFORM);
        }
        if (version == null || version.isBlank()) {
            throw invalid(MISSING_VERSION);
        }
        String sentText = version.strip();
        AppVersion sent = AppVersion.tryParse(sentText).orElseThrow(() -> invalid(INVALID_VERSION));
        return store.find(appIdentity, appPlatform)
                .map(policy -> policy.evaluate(sent, sentText))
                .orElseGet(() -> VersionCheck.nothingRecorded(sentText));
    }

    private static DomainException invalid(String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message);
    }
}
