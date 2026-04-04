package com.keevo.messaging.notification.adapter.out.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration that creates FcmProperties, WassenderProperties, and HTTP clients.
 */
@Configuration
public class MessagingPropertiesConfig {

    @Bean
    public FcmProperties fcmProperties(Environment env) {
        boolean enabled = Boolean.parseBoolean(env.getProperty("keevo.fcm.enabled", "false"));
        String credentialsPath = env.getProperty("keevo.fcm.credentials-path", "");
        return new FcmProperties(enabled, credentialsPath);
    }

    @Bean
    public WassenderProperties wassenderProperties(Environment env) {
        String apiUrl = env.getProperty("keevo.whatsapp.wassender.api-url", "");
        String apiToken = env.getProperty("keevo.whatsapp.wassender.api-token", "");
        return new WassenderProperties(apiUrl, apiToken);
    }

    /** RestTemplate with Wassender-specific timeouts: connect 10s, read 30s (AC5). */
    @Bean("wassenderRestTemplate")
    public RestTemplate wassenderRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }
}
