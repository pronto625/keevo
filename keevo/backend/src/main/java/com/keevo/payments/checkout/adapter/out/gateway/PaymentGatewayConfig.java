package com.keevo.payments.checkout.adapter.out.gateway;

import com.keevo.payments.checkout.application.service.PaymentSettings;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Locale;

/**
 * Story 21.1 — chooses the Mobile Money gateway (strategy) from {@code keevo.payments.gateway} and validates it at
 * startup: {@code disabled} (default), {@code sandbox} (allowed under the {@code dev} / {@code test} profiles only,
 * never with {@code prod} / {@code staging}: it would let anyone who knows the sandbox key activate a plan), {@code mycoolpay} (base URL, public key, private key and
 * callback hash required, from the environment only). Errors name the variable, never its value.
 */
@Configuration
public class PaymentGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    static final String GATEWAY = "keevo.payments.gateway";
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 30_000;

    @Bean
    public PaymentSettings paymentSettings(Environment env) {
        return new PaymentSettings(
                env.getProperty("keevo.payments.initiating-timeout", Duration.class, Duration.ofMinutes(2)),
                env.getProperty("keevo.payments.pending-timeout", Duration.class, Duration.ofMinutes(10)),
                env.getProperty("keevo.payments.mycoolpay.callback-hash", ""),
                env.getProperty("keevo.payments.max-checkouts-per-hour", Integer.class, 5));
    }

    @Bean
    public MobileMoneyGateway mobileMoneyGateway(Environment env) {
        return create(env);
    }

    /** The gateway of this environment, validated (also used by tests). */
    static MobileMoneyGateway create(Environment env) {
        String kind = env.getProperty(GATEWAY, "disabled").trim().toLowerCase(Locale.ROOT);
        String privateKey = env.getProperty("keevo.payments.mycoolpay.private-key", "");
        switch (kind) {
            case "disabled", "" -> {
                log.warn("[Payments] Mobile Money disabled ({}=disabled): paid checkouts answer 503", GATEWAY);
                return new DisabledMobileMoneyGateway();
            }
            case "sandbox" -> {
                if (!env.acceptsProfiles(Profiles.of("dev", "test"))
                        || env.acceptsProfiles(Profiles.of("prod", "staging"))) {
                    throw new IllegalStateException(GATEWAY + "=sandbox is allowed under the dev and test profiles "
                            + "only: set KEEVO_PAYMENTS_GATEWAY=mycoolpay (with the MyCoolPay keys) or disabled");
                }
                require(privateKey, "MYCOOLPAY_PRIVATE_KEY (sandbox signing key)");
                require(env.getProperty("keevo.payments.mycoolpay.callback-hash", ""),
                        "MYCOOLPAY_CALLBACK_HASH (sandbox webhook secret)");
                log.warn("[Payments] Mobile Money SANDBOX: simulated provider, no real money");
                return new SandboxMobileMoneyGateway(privateKey);
            }
            case "mycoolpay" -> {
                String baseUrl = env.getProperty("keevo.payments.mycoolpay.base-url", "");
                String publicKey = env.getProperty("keevo.payments.mycoolpay.public-key", "");
                require(baseUrl, "MYCOOLPAY_BASE_URL");
                require(publicKey, "MYCOOLPAY_PUBLIC_KEY");
                require(privateKey, "MYCOOLPAY_PRIVATE_KEY");
                require(env.getProperty("keevo.payments.mycoolpay.callback-hash", ""), "MYCOOLPAY_CALLBACK_HASH");
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
                factory.setReadTimeout(READ_TIMEOUT_MS);
                log.info("[Payments] Mobile Money provider: MyCoolPay");
                return new MyCoolPayGateway(RestClient.builder().baseUrl(baseUrl.trim()).requestFactory(factory)
                        .build(), publicKey.trim(), privateKey.trim());
            }
            default -> throw new IllegalStateException(GATEWAY + " must be disabled, sandbox or mycoolpay");
        }
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("[Payments] " + variable + " is required by " + GATEWAY);
        }
    }
}
