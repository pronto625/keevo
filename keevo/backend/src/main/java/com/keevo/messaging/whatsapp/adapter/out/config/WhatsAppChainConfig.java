package com.keevo.messaging.whatsapp.adapter.out.config;

import com.keevo.messaging.channel.adapter.out.config.ChannelPropertiesConfig;
import com.keevo.messaging.whatsapp.application.ChainWhatsAppSender;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import com.keevo.shared.capability.notification.WhatsAppSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Locale;

/**
 * Story 19.9b — the single platform {@link WhatsAppSender} (Factory): a {@link ChainWhatsAppSender} whose ordered
 * providers come from the {@code keevo.whatsapp.provider} preset. It replaces the former per-value conditional
 * adapters; the values keep their meaning:
 *
 * <ul>
 *   <li>{@code noop} (default) — the logging stub;</li>
 *   <li>{@code wassender}, {@code twilio} — that provider alone;</li>
 *   <li>{@code failover} — Wassender then Twilio;</li>
 *   <li>{@code meta} — Meta Cloud API (sign-in codes by template) then Wassender then Twilio.</li>
 * </ul>
 *
 * An unknown value fails the startup in every profile (missing keys fail it only in prod / staging, see
 * {@code ProdSecretsValidator}).
 */
@Configuration
public class WhatsAppChainConfig {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChainConfig.class);

    static final String PROPERTY = "keevo.whatsapp.provider";

    @Bean
    public MetaWhatsAppProperties metaWhatsAppProperties(Environment env) {
        return new MetaWhatsAppProperties(
                env.getProperty("keevo.whatsapp.meta.access-token", ""),
                env.getProperty("keevo.whatsapp.meta.phone-number-id", ""),
                env.getProperty("keevo.whatsapp.meta.otp-template", MetaWhatsAppProperties.DEFAULT_OTP_TEMPLATE));
    }

    @Bean
    public WhatsAppSender whatsAppSender(Environment env, List<WhatsAppProvider> available) {
        String preset = env.getProperty(PROPERTY, "noop");
        List<String> names = presetProviders(preset);
        log.info("[WhatsApp] preset {} → providers {}", preset.strip().toLowerCase(Locale.ROOT), names);
        return new ChainWhatsAppSender(ChannelPropertiesConfig.ordered(PROPERTY, names, available,
                WhatsAppProvider::name));
    }

    /** Provider names of a preset, in chain order; blank = {@code noop}. */
    public static List<String> presetProviders(String preset) {
        String value = preset == null ? "" : preset.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "noop" -> List.of("noop");
            case "wassender" -> List.of("wassender");
            case "twilio" -> List.of("twilio");
            case "failover" -> List.of("wassender", "twilio");
            case "meta" -> List.of("meta", "wassender", "twilio");
            default -> throw new IllegalStateException("[WhatsApp] " + PROPERTY + " has an unknown value '" + value
                    + "' — expected one of noop, wassender, twilio, failover, meta");
        };
    }
}
