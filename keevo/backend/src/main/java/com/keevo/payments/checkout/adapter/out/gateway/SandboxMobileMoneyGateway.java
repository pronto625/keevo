package com.keevo.payments.checkout.adapter.out.gateway;

import com.keevo.payments.checkout.domain.model.MobileMoneyMethod;
import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 21.1 — simulated Mobile Money provider of the tests and local runs (never in prod/staging: refused at startup).
 * No network call. Behaviour:
 * <ul>
 *   <li>initiate: Orange Money asks for an OTP, MTN prompts the phone with USSD {@value #USSD}; the number
 *       {@value #FAILING_PHONE} simulates a provider failure;</li>
 *   <li>authorize: code {@value #WRONG_CODE} is refused, any other is accepted;</li>
 *   <li>status: PENDING until a callback signed with the configured private key (same md5 as MyCoolPay) or
 *       {@link #simulate} sets the outcome — the amount is the callback's;</li>
 * </ul>
 * State in memory (lost on restart).
 */
public class SandboxMobileMoneyGateway implements MobileMoneyGateway {

    public static final String USSD = "*126#";
    public static final String FAILING_PHONE = "+237600000000";
    public static final String WRONG_CODE = "000000";

    private record Outcome(ProviderStatus status, Long amount, String currency) {
    }

    private final String privateKey;
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();

    public SandboxMobileMoneyGateway(String privateKey) {
        this.privateKey = privateKey;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.SANDBOX;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public PayinResult initiate(Payin payin) {
        if (FAILING_PHONE.equals(payin.phone())) {
            throw new GatewayException("sandbox: simulated provider failure");
        }
        String ref = "SBX-" + UUID.randomUUID();
        outcomes.put(ref, new Outcome(ProviderStatus.PENDING, null, payin.currency()));
        return payin.method() == MobileMoneyMethod.ORANGE_MONEY
                ? new PayinResult(ref, ProviderStatus.REQUIRE_OTP, null)
                : new PayinResult(ref, ProviderStatus.PENDING, USSD);
    }

    @Override
    public AuthorizeResult authorize(String providerRef, String code) {
        return WRONG_CODE.equals(code) ? new AuthorizeResult(false, null) : new AuthorizeResult(true, null);
    }

    @Override
    public StatusResult status(String providerRef) {
        Outcome o = outcomes.get(providerRef);
        return o == null ? new StatusResult(ProviderStatus.PENDING, null, "XAF")
                : new StatusResult(o.status(), o.amount(), o.currency());
    }

    @Override
    public CallbackResult verifyCallback(Map<String, Object> payload) {
        if (!MyCoolPaySignature.verify(payload, privateKey)) {
            return CallbackResult.INVALID;
        }
        String ref = payload.get("transaction_ref") == null ? null : payload.get("transaction_ref").toString();
        ProviderStatus status = MyCoolPayGateway.map(payload.get("transaction_status") == null ? null
                : payload.get("transaction_status").toString());
        if (ref != null) {
            Object currency = payload.get("transaction_currency");
            simulate(ref, status, MyCoolPayGateway.amount(payload.get("transaction_amount")),
                    currency == null ? "XAF" : currency.toString());
        }
        Object appRef = payload.get("app_transaction_ref");
        return new CallbackResult(true, ref, status, appRef == null ? null : appRef.toString());
    }

    /** Sets what {@link #status} answers for a reference (tests: the provider confirmed without a callback). */
    public void simulate(String providerRef, ProviderStatus status, Long amount, String currency) {
        outcomes.put(providerRef, new Outcome(status, amount, currency));
    }
}
