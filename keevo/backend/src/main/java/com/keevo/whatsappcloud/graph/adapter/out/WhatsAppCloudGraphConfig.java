package com.keevo.whatsappcloud.graph.adapter.out;

import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import com.keevo.whatsappcloud.config.WhatsAppCloudSettings;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Locale;

/**
 * Story 21.5 — chooses the Meta Graph strategy from {@code keevo.whatsapp-cloud.graph} and validates it at startup
 * (21.1's pattern): {@code disabled} (default: connecting answers 503), {@code fake} (dev / test profiles only, never
 * with prod / staging), {@code meta} (app id, app secret, webhook verify token and {@code KEEVO_SECRETS_ENCRYPTION_KEY}
 * required, from the environment only). Errors name the variable, never its value.
 */
@Configuration
public class WhatsAppCloudGraphConfig {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppCloudGraphConfig.class);

    static final String MODE = "keevo.whatsapp-cloud.graph";
    static final int CONNECT_TIMEOUT_MS = 5_000;
    static final int READ_TIMEOUT_MS = 30_000;

    @Bean
    public WhatsAppCloudSettings whatsAppCloudSettings(Environment env) {
        return new WhatsAppCloudSettings(env.getProperty("keevo.whatsapp-cloud.app-secret", ""),
                env.getProperty("keevo.whatsapp-cloud.verify-token", ""));
    }

    /** Story 21.6 — the platform number ({@code keevo.whatsapp-cloud.platform.*}). */
    @Bean
    public com.keevo.whatsappcloud.config.PlatformNumberSettings platformNumberSettings(Environment env) {
        return new com.keevo.whatsappcloud.config.PlatformNumberSettings(
                env.getProperty("keevo.whatsapp-cloud.platform.phone-number-id", ""),
                env.getProperty("keevo.whatsapp-cloud.platform.waba-id", ""),
                env.getProperty("keevo.whatsapp-cloud.platform.access-token", ""));
    }

    @Bean
    public MetaGraphClient metaGraphClient(Environment env, SecretCipher cipher) {
        return create(env, cipher);
    }

    /** The strategy of this environment, validated (also used by tests). */
    static MetaGraphClient create(Environment env, SecretCipher cipher) {
        String mode = env.getProperty(MODE, "disabled").trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "disabled", "" -> {
                log.info("[WhatsApp Cloud] disabled ({}=disabled): companies cannot connect their WhatsApp Business",
                        MODE);
                return new DisabledMetaGraphClient();
            }
            case "fake" -> {
                if (!env.acceptsProfiles(Profiles.of("dev", "test"))
                        || env.acceptsProfiles(Profiles.of("prod", "staging"))) {
                    throw new IllegalStateException(MODE + "=fake is allowed under the dev and test profiles only: set "
                            + "KEEVO_WHATSAPP_CLOUD_GRAPH=meta (with the Meta app keys) or disabled");
                }
                log.warn("[WhatsApp Cloud] FAKE Graph API: nothing reaches Meta");
                return new FakeMetaGraphClient();
            }
            case "meta" -> {
                String appId = env.getProperty("keevo.whatsapp-cloud.app-id", "");
                String appSecret = env.getProperty("keevo.whatsapp-cloud.app-secret", "");
                require(appId, "KEEVO_WHATSAPP_CLOUD_APP_ID");
                require(appSecret, "KEEVO_WHATSAPP_CLOUD_APP_SECRET");
                require(env.getProperty("keevo.whatsapp-cloud.verify-token", ""),
                        "KEEVO_WHATSAPP_CLOUD_VERIFY_TOKEN");
                if (!cipher.isAvailable()) {
                    throw new IllegalStateException("[WhatsApp Cloud] KEEVO_SECRETS_ENCRYPTION_KEY is required by " + MODE
                            + "=meta (access tokens are stored encrypted)");
                }
                String baseUrl = env.getProperty("keevo.whatsapp-cloud.graph-url",
                        "https://graph.facebook.com/v21.0").trim();
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
                factory.setReadTimeout(READ_TIMEOUT_MS);
                log.info("[WhatsApp Cloud] Meta Graph API enabled");
                return new HttpMetaGraphClient(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(),
                        appId.trim(), appSecret.trim());
            }
            default -> throw new IllegalStateException(MODE + " must be disabled, fake or meta");
        }
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("[WhatsApp Cloud] " + variable + " is required by " + MODE + "=meta");
        }
    }
}
