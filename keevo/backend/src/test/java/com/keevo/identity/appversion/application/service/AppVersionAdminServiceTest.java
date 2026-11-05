package com.keevo.identity.appversion.application.service;

import com.keevo.identity.api.AppVersionAdminPort;
import com.keevo.identity.appversion.domain.model.AppPlatform;
import com.keevo.identity.appversion.domain.model.AppVersionPolicy;
import com.keevo.identity.appversion.domain.port.out.AppVersionPolicyStore;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("19.10 — AppVersionAdminService")
class AppVersionAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final AppVersionPolicyStore store = mock(AppVersionPolicyStore.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final AppVersionAdminService service;
    private final UUID actor = UUID.randomUUID();

    AppVersionAdminServiceTest() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new AppVersionAdminService(store, txManager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("create: values stripped, blank = null, updated_by / updated_at set, 'created' reported")
    void creates() {
        when(store.find("bref", AppPlatform.ANDROID)).thenReturn(Optional.empty());
        when(store.upsert(any())).thenReturn(true);

        AppVersionAdminPort.SaveResult r = service.save("bref", "Android", " 2.1.0 ", "2.2.0",
                "https://play.google.com/store/apps/details?id=x", "  ", actor);

        assertThat(r.created()).isTrue();
        assertThat(r.policy()).isEqualTo(new AppVersionAdminPort.AppVersionPolicyView("bref", "android", "2.1.0",
                "2.2.0", "https://play.google.com/store/apps/details?id=x", null, NOW));
        ArgumentCaptor<AppVersionPolicy> saved = ArgumentCaptor.forClass(AppVersionPolicy.class);
        verify(store).upsert(saved.capture());
        assertThat(saved.getValue().updatedBy()).isEqualTo(actor);
        assertThat(saved.getValue().updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("replace: 'created' false; all values may be null")
    void replaces() {
        when(store.find("keevo", AppPlatform.IOS)).thenReturn(Optional.of(new AppVersionPolicy("keevo",
                AppPlatform.IOS, "1.0", null, null, null, NOW, null)));
        when(store.upsert(any())).thenReturn(false);
        assertThat(service.save("keevo", "ios", null, null, null, null, actor).created()).isFalse();
    }

    @ParameterizedTest(name = "{0}/{1} min={2} latest={3} url={4}")
    @CsvSource(nullValues = "null", delimiter = '|', quoteCharacter = '"', value = {
            "Bref|android|null|null|null|L'identifiant de l'application doit contenir uniquement des minuscules, des chiffres et des tirets, 30 caractères au plus (exemple : bref)",
            "bref-app-with-a-very-long-identity|android|null|null|null|L'identifiant de l'application doit contenir uniquement des minuscules, des chiffres et des tirets, 30 caractères au plus (exemple : bref)",
            "bref|windows|null|null|null|La plateforme doit être ios, android ou web",
            "bref|android|abc|null|null|La version doit être de la forme 2.1.0",
            "bref|android|1.0.0.0.0.0.0.0.0.0.0|null|null|La version doit être de la forme 2.1.0",
            "bref|android|null|2.x|null|La version doit être de la forme 2.1.0",
            "bref|android|2.1.0-beta|null|null|La version doit être de la forme 2.1.0",
            "bref|android|null|2.2.0+80|null|La version doit être de la forme 2.1.0",
            "bref|android|2.1.0|2.0.0|null|La version minimale dépasse la dernière version",
            "bref|android|null|null|http://play.google.com/x|Le lien du store doit être une adresse commençant par https://",
            "bref|android|null|null|https://|Le lien du store doit être une adresse commençant par https://"
    })
    @DisplayName("invalid → 422 APP_VERSION_INVALID with a French reason, nothing saved")
    void refuses(String identity, String platform, String min, String latest, String url, String message) {
        assertThatThrownBy(() -> service.save(identity, platform, min, latest, url, null, actor))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APP_VERSION_INVALID);
                    assertThat(e.getUserMessage()).isEqualTo(message);
                });
        verify(store, never()).upsert(any());
    }

    @Test
    @DisplayName("over-long store link or message → 422")
    void refusesOverLong() {
        assertThatThrownBy(() -> service.save("bref", "ios", null, null, "https://x.example/" + "a".repeat(500),
                null, actor)).isInstanceOfSatisfying(DomainException.class, e ->
                assertThat(e.getUserMessage()).isEqualTo("Le lien du store ne doit pas dépasser 500 caractères"));
        assertThatThrownBy(() -> service.save("bref", "ios", null, null, null, "m".repeat(501), actor))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getUserMessage()).isEqualTo("Le message ne doit pas dépasser 500 caractères"));
        verify(store, never()).upsert(any());
    }

    @Test
    @DisplayName("min = latest is accepted")
    void minEqualsLatest() {
        when(store.find(any(), any())).thenReturn(Optional.empty());
        when(store.upsert(any())).thenReturn(true);
        assertThat(service.save("bref", "web", "2.1", "2.1.0", null, null, actor).created()).isTrue();
    }

    @Test
    @DisplayName("an uppercase HTTPS:// scheme is accepted")
    void uppercaseHttps() {
        when(store.find(any(), any())).thenReturn(Optional.empty());
        when(store.upsert(any())).thenReturn(true);
        assertThat(service.save("bref", "ios", null, null, "HTTPS://apps.apple.com/app/id1", null, actor)
                .policy().storeUrl()).isEqualTo("HTTPS://apps.apple.com/app/id1");
    }

    @Test
    @DisplayName("delete: one DELETE … RETURNING; absent or invalid → 404 APP_VERSION_NOT_FOUND, no read")
    void deletes() {
        when(store.delete("bref", AppPlatform.IOS)).thenReturn(Optional.of(new AppVersionPolicy("bref",
                AppPlatform.IOS, "1.0", null, null, null, NOW, null)));
        service.delete("bref", "ios", actor);
        verify(store).delete("bref", AppPlatform.IOS);
        verify(store, never()).find(any(), any());

        when(store.delete("bref", AppPlatform.WEB)).thenReturn(Optional.empty());
        for (String[] key : new String[][]{{"bref", "web"}, {"Bref", "ios"}, {"bref", "windows"}}) {
            assertThatThrownBy(() -> service.delete(key[0], key[1], actor))
                    .isInstanceOfSatisfying(DomainException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APP_VERSION_NOT_FOUND));
        }
    }
}
