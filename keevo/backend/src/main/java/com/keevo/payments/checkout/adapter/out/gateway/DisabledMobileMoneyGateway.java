package com.keevo.payments.checkout.adapter.out.gateway;

import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;

import java.util.Map;

/**
 * Story 21.1 — no Mobile Money provider configured ({@code keevo.payments.gateway=disabled}, the default): a paid
 * checkout is refused before anything is stored, every callback is refused; free offers still work.
 */
public class DisabledMobileMoneyGateway implements MobileMoneyGateway {

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.NONE;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public PayinResult initiate(Payin payin) {
        throw new GatewayException("no Mobile Money gateway configured");
    }

    @Override
    public AuthorizeResult authorize(String providerRef, String code) {
        throw new GatewayException("no Mobile Money gateway configured");
    }

    @Override
    public StatusResult status(String providerRef) {
        throw new GatewayException("no Mobile Money gateway configured");
    }

    @Override
    public CallbackResult verifyCallback(Map<String, Object> payload) {
        return CallbackResult.INVALID;
    }
}
