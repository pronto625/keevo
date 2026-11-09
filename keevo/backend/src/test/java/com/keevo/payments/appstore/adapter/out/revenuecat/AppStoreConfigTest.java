package com.keevo.payments.appstore.adapter.out.revenuecat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AppStoreConfig — App Store settings and RevenueCat client validated at startup (Story 21.2)")
class AppStoreConfigTest {

    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    @DisplayName("default: no secret (webhook refused), sandbox refused, client disabled")
    void defaults() {
        MockEnvironment env = new MockEnvironment();
        var settings = AppStoreConfig.settings(env);
        assertThat(settings.webhookSecret()).isEmpty();
        assertThat(settings.acceptSandbox()).isFalse();
        assertThat(settings.reviewAccountEmail()).isNull();
        assertThat(AppStoreConfig.client(env, CLOCK)).isInstanceOf(DisabledRevenueCatClient.class);
        assertThat(settings.toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("keys set → REST client; review email lower-cased; secret never printed")
    void configured() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("keevo.payments.app-store.webhook-secret", "Bearer s3cr3t")
                .withProperty("keevo.payments.app-store.review-account-email", " Review@Apple.COM ")
                .withProperty("keevo.payments.app-store.revenuecat.secret-key", "sk_1")
                .withProperty("keevo.payments.app-store.revenuecat.project-id", "proj1");
        var settings = AppStoreConfig.settings(env);
        assertThat(settings.reviewAccountEmail()).isEqualTo("review@apple.com");
        assertThat(settings.toString()).doesNotContain("s3cr3t");
        assertThat(AppStoreConfig.client(env, CLOCK)).isInstanceOf(RevenueCatRestClient.class);
    }

    @Test
    @DisplayName("accept-sandbox and the fake client: dev / test profiles only")
    void devOnly() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("keevo.payments.app-store.accept-sandbox", "true")
                .withProperty("keevo.payments.app-store.revenuecat.mode", "fake");
        assertThatThrownBy(() -> AppStoreConfig.settings(env)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AppStoreConfig.client(env, CLOCK)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("dev");
        assertThat(AppStoreConfig.settings(env).acceptSandbox()).isTrue();
        assertThat(AppStoreConfig.client(env, CLOCK)).isInstanceOf(InMemoryRevenueCatClient.class);
        env.setActiveProfiles("test", "prod");
        assertThatThrownBy(() -> AppStoreConfig.settings(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("keevo.payments.app-store.revenuecat.mode", "other");
        env.setActiveProfiles("dev");
        assertThatThrownBy(() -> AppStoreConfig.client(env, CLOCK)).isInstanceOf(IllegalStateException.class);
    }
}
