package com.keevo.payments.appstore.adapter.out.revenuecat;

import com.keevo.payments.appstore.application.service.AppStoreSettings;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.Locale;

/**
 * Story 21.2 — App Store settings and the RevenueCat client (strategy), validated at startup. Client
 * ({@code keevo.payments.app-store.revenuecat.mode}): {@code auto} (default) = the REST client when the secret key and
 * the project id are set, else disabled; {@code fake} = in-memory, {@code dev}/{@code test} profiles only.
 * {@code accept-sandbox=true} is likewise refused outside {@code dev}/{@code test}. Errors name the variable, never its
 * value.
 */
@Configuration
public class AppStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(AppStoreConfig.class);
    static final String PREFIX = "keevo.payments.app-store.";
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 30_000;

    @Bean
    public AppStoreSettings appStoreSettings(Environment env) {
        return settings(env);
    }

    @Bean
    public RevenueCatClient revenueCatClient(Environment env, Clock clock) {
        return client(env, clock);
    }

    static AppStoreSettings settings(Environment env) {
        boolean acceptSandbox = env.getProperty(PREFIX + "accept-sandbox", Boolean.class, false);
        if (acceptSandbox && !devOrTest(env)) {
            throw new IllegalStateException(PREFIX + "accept-sandbox=true is allowed under the dev and test profiles "
                    + "only (sandbox purchases would activate real plans); use REVIEW_ACCOUNT_EMAIL for App Review");
        }
        String secret = env.getProperty(PREFIX + "webhook-secret", "");
        if (secret.isBlank()) {
            log.warn("[AppStore] REVENUECAT_WEBHOOK_SECRET not set: every RevenueCat webhook call is refused (401)");
        }
        String review = env.getProperty(PREFIX + "review-account-email", "").trim().toLowerCase(Locale.ROOT);
        return new AppStoreSettings(secret, acceptSandbox, review.isEmpty() ? null : review);
    }

    static RevenueCatClient client(Environment env, Clock clock) {
        String mode = env.getProperty(PREFIX + "revenuecat.mode", "auto").trim().toLowerCase(Locale.ROOT);
        String key = env.getProperty(PREFIX + "revenuecat.secret-key", "").trim();
        String project = env.getProperty(PREFIX + "revenuecat.project-id", "").trim();
        switch (mode) {
            case "fake" -> {
                if (!devOrTest(env)) {
                    throw new IllegalStateException(PREFIX + "revenuecat.mode=fake is allowed under the dev and test "
                            + "profiles only");
                }
                log.warn("[AppStore] RevenueCat SIMULATED (in-memory), no real call");
                return new InMemoryRevenueCatClient();
            }
            case "auto", "" -> {
                if (key.isEmpty() || project.isEmpty()) {
                    log.warn("[AppStore] REVENUECAT_SECRET_KEY / REVENUECAT_PROJECT_ID not set: owner sync answers 503,"
                            + " TRANSFER events are redelivered until configured");
                    return new DisabledRevenueCatClient();
                }
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
                factory.setReadTimeout(READ_TIMEOUT_MS);
                String baseUrl = env.getProperty(PREFIX + "revenuecat.base-url", "https://api.revenuecat.com").trim();
                log.info("[AppStore] RevenueCat REST client enabled");
                return new RevenueCatRestClient(RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                        .defaultHeader("Authorization", "Bearer " + key).build(), project, clock);
            }
            default -> throw new IllegalStateException(PREFIX + "revenuecat.mode must be auto or fake");
        }
    }

    private static boolean devOrTest(Environment env) {
        return env.acceptsProfiles(Profiles.of("dev", "test")) && !env.acceptsProfiles(Profiles.of("prod", "staging"));
    }
}
