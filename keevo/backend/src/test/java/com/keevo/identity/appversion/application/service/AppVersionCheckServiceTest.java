package com.keevo.identity.appversion.application.service;

import com.keevo.identity.appversion.domain.model.AppPlatform;
import com.keevo.identity.appversion.domain.model.AppVersionPolicy;
import com.keevo.identity.appversion.domain.model.VersionCheck;
import com.keevo.identity.appversion.domain.port.out.AppVersionPolicyStore;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("19.10 — AppVersionCheckService (I/O matrix)")
class AppVersionCheckServiceTest {

    private final AppVersionPolicyStore store = mock(AppVersionPolicyStore.class);
    private final AppVersionCheckService service = new AppVersionCheckService(store);

    private static AppVersionPolicy policy(String min, String latest) {
        return new AppVersionPolicy("bref", AppPlatform.ANDROID, min, latest, "https://play.example/app",
                "Nouvelle version", Instant.now(), null);
    }

    @BeforeEach
    void setUp() {
        when(store.find(anyString(), any())).thenReturn(Optional.empty());
        when(store.find("bref", AppPlatform.ANDROID)).thenReturn(Optional.of(policy("2.1.0", "2.2.0")));
    }

    @Test
    @DisplayName("forced: below min → required and available, latestVersion recorded (no identity = bref)")
    void forced() {
        VersionCheck c = service.check(null, "android", "2.0.0");
        assertThat(c).isEqualTo(new VersionCheck(true, true, "https://play.example/app", "Nouvelle version",
                "2.2.0"));
        verify(store, times(1)).find("bref", AppPlatform.ANDROID);
    }

    @Test
    @DisplayName("optional: between min and latest → available only")
    void optional() {
        VersionCheck c = service.check("", "ANDROID", "2.1.5");
        assertThat(c.updateRequired()).isFalse();
        assertThat(c.updateAvailable()).isTrue();
    }

    @Test
    @DisplayName("up to date: build suffix ignored → all false")
    void upToDate() {
        VersionCheck c = service.check("bref", "Android", "2.2.0+80");
        assertThat(c.updateRequired()).isFalse();
        assertThat(c.updateAvailable()).isFalse();
        assertThat(c.latestVersion()).isEqualTo("2.2.0");
    }

    @Test
    @DisplayName("identity is case-insensitive: Bref = bref")
    void identityCaseInsensitive() {
        assertThat(service.check(" Bref ", "android", "2.0.0").updateRequired()).isTrue();
        verify(store).find("bref", AppPlatform.ANDROID);
    }

    @Test
    @DisplayName("no row → all false, latestVersion = sent version, nulls")
    void noRow() {
        assertThat(service.check("keevo", "ios", "1.0"))
                .isEqualTo(new VersionCheck(false, false, null, null, "1.0"));
    }

    @ParameterizedTest(name = "min={0} latest={1} version={2} → required={3} available={4} latest={5}")
    @CsvSource(nullValues = "null", value = {
            "2.1.0, null, 2.0.0, true, true, 2.1.0",
            "2.1.0, garbage, 2.0.0, true, true, 2.1.0",
            "2.1.0, null, 2.1.0, false, false, 2.1.0",
            "null, 2.2.0, 2.0.0, false, true, 2.2.0",
            "null, null, 2.0.0, false, false, 2.0.0",
            "garbage, 2.2.0, 1.0, false, true, 2.2.0",
            "garbage, garbage, 1.0, false, false, 1.0"
    })
    @DisplayName("partial rows: absent (or hand-edited invalid) bounds are ignored and never returned; latestVersion = "
            + "stored latest, else the minimum when required, else the sent version")
    void partialRows(String min, String latest, String version, boolean required, boolean available,
                     String latestVersion) {
        when(store.find("bref", AppPlatform.WEB)).thenReturn(Optional.of(new AppVersionPolicy("bref",
                AppPlatform.WEB, min, latest, null, null, Instant.now(), null)));
        VersionCheck c = service.check(null, "web", version);
        assertThat(c.updateRequired()).isEqualTo(required);
        assertThat(c.updateAvailable()).isEqualTo(available);
        assertThat(c.latestVersion()).isEqualTo(latestVersion);
    }

    @ParameterizedTest(name = "identity={0} platform={1} version={2}")
    @CsvSource(nullValues = "null", value = {
            "null, windows, 2.0.0, 'Plateforme inconnue : indiquez ios, android ou web'",
            "null, null, 2.0.0, 'Plateforme inconnue : indiquez ios, android ou web'",
            "null, android, abc, 'Version de l''application invalide (exemple : 2.1.0)'",
            "null, android, null, 'Indiquez la version de l''application'",
            "null, android, '  ', 'Indiquez la version de l''application'",
            "Bad_Id, android, 2.0.0, Application inconnue : identifiant invalide"
    })
    @DisplayName("bad input → 422 VALIDATION_ERROR with a French message, no read")
    void badInput(String identity, String platform, String version, String message) {
        assertThatThrownBy(() -> service.check(identity, platform, version))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getUserMessage()).isEqualTo(message);
                });
        verify(store, never()).find(anyString(), any());
    }
}
