package com.keevo.payments.checkout.application.service;

import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.CallbackResult;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.ProviderStatus;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Story 21.1 — the provider callback (port of Bref's {@code PaymentGatewayWebhookController}): secret path segment
 * (constant time), signature ({@link MobileMoneyGateway#verifyCallback}), routing by the provider reference (unique
 * index), then the server-to-server status decides ({@link PaymentSettlementService}). A reference Keevo does not know
 * — an owner still served by api-bref during the migration (24.10) — is acknowledged and ignored, never an error.
 * Idempotent: a replay finds the request CONFIRMED and changes nothing. Logs never carry the payload, the path secret
 * or a phone number.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    /**
     * {@link #ACCEPTED} / {@link #IGNORED}: answer « OK »; {@link #REJECTED}: « KO » (bad secret, bad signature or a
     * success the provider does not confirm — it will retry); {@link #RETRY}: provider unreachable, answer an error so
     * it retries.
     */
    public enum Result { ACCEPTED, IGNORED, REJECTED, RETRY }

    private final MobileMoneyGateway gateway;
    private final PaymentRequestStore store;
    private final PaymentSettlementService settlement;
    private final PaymentSettings settings;

    public PaymentWebhookService(MobileMoneyGateway gateway, PaymentRequestStore store,
                                 PaymentSettlementService settlement, PaymentSettings settings) {
        this.gateway = gateway;
        this.store = store;
        this.settlement = settlement;
        this.settings = settings;
    }

    /** A MyCoolPay callback (also simulated by the sandbox gateway, same signature). */
    public Result handle(String hash, Map<String, Object> payload) {
        if (!gateway.available()) {
            log.warn("Payment webhook: no gateway configured — redelivery requested");
            return Result.RETRY;
        }
        if (!secretMatches(hash)) {
            log.warn("Payment webhook refused: unknown callback secret");
            return Result.REJECTED;
        }
        CallbackResult callback = payload == null ? CallbackResult.INVALID : gateway.verifyCallback(payload);
        if (!callback.valid() || callback.providerRef() == null || callback.providerRef().isBlank()) {
            log.warn("Payment webhook refused: invalid signature");
            return Result.REJECTED;
        }
        PaymentRequest request = store.findByProviderRef(gateway.provider(), callback.providerRef())
                .orElseGet(() -> byAppRef(callback));
        if (request == null) {
            log.info("Payment webhook ignored: reference unknown to Keevo (owner not migrated yet)");
            return Result.IGNORED;
        }
        if (callback.status() == ProviderStatus.SUCCESS) {
            return switch (settlement.settle(request)) {
                case CONFIRMED, ALREADY_CONFIRMED, FAILED -> Result.ACCEPTED;
                case PROVIDER_UNREACHABLE -> Result.RETRY;
                case NOT_VERIFIED, PENDING -> Result.REJECTED;
            };
        }
        if (callback.status() == ProviderStatus.FAILED || callback.status() == ProviderStatus.CANCELED) {
            store.markFailed(request.id(), PaymentStatus.IN_FLIGHT, PaymentText.PROVIDER_FAILED,
                    callback.status().name());
        }
        return Result.ACCEPTED;
    }

    /**
     * Our own reference echoed by the provider, for a request whose provider reference was never stored (the
     * initiation timed out, or the callback came before the answer was recorded): the provider reference is attached
     * (only when none is stored) and the request read again.
     */
    private PaymentRequest byAppRef(CallbackResult callback) {
        String appRef = callback.appTransactionRef();
        if (appRef == null || !appRef.startsWith(CheckoutService.APP_REF_PREFIX)) {
            return null;
        }
        PaymentRequest r = store.findByAppRef(appRef).orElse(null);
        if (r == null || r.provider() != gateway.provider() || r.providerTransactionRef() != null) {
            return null;
        }
        store.attachProviderRef(r.id(), callback.providerRef());
        log.info("Payment webhook: request {} routed by our reference", r.id());
        return store.find(r.id()).orElse(null);
    }

    private boolean secretMatches(String hash) {
        String expected = settings.callbackHash();
        if (expected == null || expected.isBlank() || hash == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8));
    }
}
