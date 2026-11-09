package com.keevo.payments.checkout.domain.port.out;

import com.keevo.payments.checkout.domain.model.MobileMoneyMethod;
import com.keevo.payments.checkout.domain.model.PaymentProvider;

import java.util.Map;

/**
 * Story 21.1 — a Mobile Money provider (port of Bref's {@code PaymentGateway}). Adapters: MyCoolPay (real money),
 * sandbox (simulated: tests and local runs), disabled (no provider configured). Calls are blocking HTTP: never inside a
 * database transaction. A provider failure throws {@link GatewayException}, whose message is technical and never shown.
 */
public interface MobileMoneyGateway {

    PaymentProvider provider();

    /** False for the disabled adapter: a paid checkout is refused (503) before anything is stored. */
    boolean available();

    /** Starts the collection: the provider answers with its reference and the next step (OTP or USSD/phone prompt). */
    PayinResult initiate(Payin payin);

    /**
     * Sends the OTP of an {@link ProviderStatus#REQUIRE_OTP} payment.
     *
     * @return {@code accepted=false} when the provider refuses the code
     */
    AuthorizeResult authorize(String providerRef, String code);

    /** Server-to-server status of a payment — the only proof of payment (a callback is only a signal). */
    StatusResult status(String providerRef);

    /** Checks the callback signature; never throws on a malformed body (invalid result). */
    CallbackResult verifyCallback(Map<String, Object> payload);

    /**
     * @param amountXaf         amount to collect (XAF, &gt; 0)
     * @param phone             E.164 ({@code +2376XXXXXXXX})
     * @param appTransactionRef our unique reference
     * @param reason            label of the transaction shown by the operator
     */
    record Payin(long amountXaf, String currency, String phone, MobileMoneyMethod method, String appTransactionRef,
                 String reason) {
    }

    enum ProviderStatus { PENDING, REQUIRE_OTP, SUCCESS, FAILED, CANCELED }

    /** @param ussdCode code the owner dials to validate, {@code null} when the operator prompts the phone itself */
    record PayinResult(String providerRef, ProviderStatus status, String ussdCode) {
    }

    record AuthorizeResult(boolean accepted, String ussdCode) {
    }

    /** @param amount amount the provider collected, {@code null} when unknown */
    record StatusResult(ProviderStatus status, Long amount, String currency) {
    }

    /**
     * @param appTransactionRef our reference echoed by the provider ({@code app_transaction_ref}): routes a callback
     *                          whose provider reference Keevo never stored (initiation timed out, callback before the
     *                          answer was recorded)
     */
    record CallbackResult(boolean valid, String providerRef, ProviderStatus status, String appTransactionRef) {

        public static final CallbackResult INVALID = new CallbackResult(false, null, null, null);
    }

    /** The provider refused or failed (HTTP error, timeout, unexpected answer). */
    class GatewayException extends RuntimeException {

        public GatewayException(String message) {
            super(message);
        }

        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
