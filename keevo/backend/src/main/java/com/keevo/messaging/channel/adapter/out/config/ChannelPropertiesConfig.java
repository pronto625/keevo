package com.keevo.messaging.channel.adapter.out.config;

import com.keevo.messaging.channel.application.FailoverEmailSender;
import com.keevo.messaging.channel.application.FailoverSmsSender;
import com.keevo.messaging.channel.domain.port.out.EmailProvider;
import com.keevo.messaging.channel.domain.port.out.SmsProvider;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.SmsSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Story 19.9 — SMS / email channel wiring: properties ({@code keevo.sms.*}, {@code keevo.email.*}), the provider
 * HTTP client (connect 5 s, read 10 s) and the failover senders, whose provider order is
 * {@code keevo.sms.providers} / {@code keevo.email.providers} (comma list; empty = channel disabled). An unknown
 * provider name fails the startup in every profile; missing keys fail it only in prod / staging
 * ({@code ProdSecretsValidator}), elsewhere the provider is skipped at send time.
 */
@Configuration
public class ChannelPropertiesConfig {

    static final int CONNECT_TIMEOUT_MS = 5_000;
    static final int READ_TIMEOUT_MS = 10_000;

    @Bean
    public SmsChannelProperties smsChannelProperties(Environment env) {
        return new SmsChannelProperties(
                parseProviders(env.getProperty("keevo.sms.providers", "")),
                env.getProperty("keevo.sms.sender-id", "BrefPoint"),
                new ProviderKeys(env.getProperty("keevo.sms.mboasms.base-url", ""),
                        env.getProperty("keevo.sms.mboasms.api-key", "")),
                new ProviderKeys(env.getProperty("keevo.sms.infobip.base-url", ""),
                        env.getProperty("keevo.sms.infobip.api-key", "")));
    }

    @Bean
    public EmailChannelProperties emailChannelProperties(Environment env) {
        return new EmailChannelProperties(
                parseProviders(env.getProperty("keevo.email.providers", "")),
                env.getProperty("keevo.email.from", ""),
                env.getProperty("keevo.email.from-name", ""),
                env.getProperty("keevo.email.resend.api-key", ""),
                new ProviderKeys(env.getProperty("keevo.email.infobip.base-url", ""),
                        env.getProperty("keevo.email.infobip.api-key", "")));
    }

    /** Provider calls: connect 5 s, read 10 s (a provider never holds a request thread longer). */
    @Bean("channelRestTemplate")
    public RestTemplate channelRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    @Bean
    public SmsSender smsSender(List<SmsProvider> available, SmsChannelProperties properties) {
        return new FailoverSmsSender(ordered("keevo.sms.providers", properties.providers(), available,
                SmsProvider::name), properties.senderId());
    }

    @Bean
    public EmailSender emailSender(List<EmailProvider> available, EmailChannelProperties properties) {
        return new FailoverEmailSender(ordered("keevo.email.providers", properties.providers(), available,
                EmailProvider::name), properties.from(), properties.fromName());
    }

    /** Lower-cased, trimmed, de-duplicated names in their configured order; blanks dropped. */
    public static List<String> parseProviders(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.copyOf(Arrays.stream(raw.split(","))
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    static <P> List<P> ordered(String property, List<String> names, List<P> available, Function<P, String> name) {
        Map<String, P> byName = available.stream().collect(Collectors.toMap(name, Function.identity()));
        List<P> chain = new ArrayList<>(names.size());
        for (String n : names) {
            P provider = byName.get(n);
            if (provider == null) {
                throw new IllegalStateException("[Messaging] " + property + " names an unknown provider '" + n
                        + "' — expected one of " + byName.keySet().stream().sorted().toList());
            }
            chain.add(provider);
        }
        return chain;
    }
}
