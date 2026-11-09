package com.keevo.payments.checkout.adapter.out.gateway;

import com.keevo.payments.checkout.domain.model.PaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentGatewayConfig — gateway choice and fail-fast validation (Story 21.1)")
class PaymentGatewayConfigTest {

    @Test
    @DisplayName("default: disabled (not available)")
    void disabledByDefault() {
        var gateway = PaymentGatewayConfig.create(new MockEnvironment());
        assertThat(gateway.available()).isFalse();
        assertThat(gateway.provider()).isEqualTo(PaymentProvider.NONE);
    }

    @Test
    @DisplayName("sandbox: allowed under dev / test only (allow-list); refused without a profile, under prod, staging")
    void sandbox() {
        MockEnvironment env = new MockEnvironment().withProperty("keevo.payments.gateway", "sandbox")
                .withProperty("keevo.payments.mycoolpay.private-key", "k")
                .withProperty("keevo.payments.mycoolpay.callback-hash", "h");
        assertThatThrownBy(() -> PaymentGatewayConfig.create(env)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("dev");
        assertThat(PaymentGatewayConfig.create(env).provider()).isEqualTo(PaymentProvider.SANDBOX);
        env.setActiveProfiles("test");
        assertThat(PaymentGatewayConfig.create(env).provider()).isEqualTo(PaymentProvider.SANDBOX);
        env.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> PaymentGatewayConfig.create(env)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> PaymentGatewayConfig.create(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox");
        env.setActiveProfiles("staging");
        assertThatThrownBy(() -> PaymentGatewayConfig.create(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("mycoolpay: every key required (message names the variable, never a value)")
    void myCoolPay() {
        MockEnvironment env = new MockEnvironment().withProperty("keevo.payments.gateway", "mycoolpay")
                .withProperty("keevo.payments.mycoolpay.base-url", "https://my-coolpay.com")
                .withProperty("keevo.payments.mycoolpay.public-key", "pub-value")
                .withProperty("keevo.payments.mycoolpay.private-key", "priv-value");
        assertThatThrownBy(() -> PaymentGatewayConfig.create(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYCOOLPAY_CALLBACK_HASH").hasMessageNotContaining("priv-value");
        env.setProperty("keevo.payments.mycoolpay.callback-hash", "hash");
        env.setActiveProfiles("prod");
        assertThat(PaymentGatewayConfig.create(env).provider()).isEqualTo(PaymentProvider.MYCOOLPAY);
        env.setProperty("keevo.payments.mycoolpay.public-key", " ");
        assertThatThrownBy(() -> PaymentGatewayConfig.create(env)).hasMessageContaining("MYCOOLPAY_PUBLIC_KEY");
        assertThatThrownBy(() -> PaymentGatewayConfig.create(new MockEnvironment()
                .withProperty("keevo.payments.gateway", "stripe"))).isInstanceOf(IllegalStateException.class);
    }
}
