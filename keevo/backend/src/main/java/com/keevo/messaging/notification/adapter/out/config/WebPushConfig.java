package com.keevo.messaging.notification.adapter.out.config;

import com.keevo.messaging.notification.domain.model.WebPushSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Story 19.9b — {@code keevo.push.web.*}: VAPID keys of Web Push, the same as Bref ({@code VAPID_PUBLIC_KEY},
 * {@code VAPID_PRIVATE_KEY}, {@code VAPID_SUBJECT}) so imported browser subscriptions stay valid. Missing keys =
 * Web Push disabled (WARN at boot by {@code WebPushSender}); nothing is forced on in any profile.
 */
@Configuration
public class WebPushConfig {

    @Bean
    public WebPushSettings webPushSettings(Environment env) {
        return new WebPushSettings(
                env.getProperty("keevo.push.web.vapid-public-key", ""),
                env.getProperty("keevo.push.web.vapid-private-key", ""),
                env.getProperty("keevo.push.web.subject", WebPushSettings.DEFAULT_SUBJECT));
    }
}
