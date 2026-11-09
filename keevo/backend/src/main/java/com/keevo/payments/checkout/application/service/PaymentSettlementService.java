package com.keevo.payments.checkout.application.service;

import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.ProviderStatus;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.StatusResult;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Story 21.1 — turns the provider's server-to-server status of a request into a state change, shared by the webhook
 * and the owner's status poll. The status (never the callback body) is the proof: SUCCESS with at least the amount
 * due, in the request currency → {@link PaymentConfirmationService#confirm} (honoured whatever the local state —
 * money received is never lost); FAILED / CANCELED → an in-flight request becomes FAILED; anything else changes
 * nothing. No transaction here: the HTTP call happens before the confirmation transaction.
 */
@Service
public class PaymentSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementService.class);

    /** What the provider said about a request. */
    public enum Settlement {
        CONFIRMED, ALREADY_CONFIRMED, FAILED, PENDING, NOT_VERIFIED, PROVIDER_UNREACHABLE,
        /** Story 21.3 — paid late, the credit no longer covered the rest: the money went to the account credit. */
        CREDITED
    }

    private final MobileMoneyGateway gateway;
    private final PaymentRequestStore store;
    private final PaymentConfirmationService confirmation;

    public PaymentSettlementService(MobileMoneyGateway gateway, PaymentRequestStore store,
                                    PaymentConfirmationService confirmation) {
        this.gateway = gateway;
        this.store = store;
        this.confirmation = confirmation;
    }

    /** Asks the provider (one HTTP call) and applies the answer. */
    public Settlement settle(PaymentRequest request) {
        if (request.status() == PaymentStatus.CONFIRMED) {
            return Settlement.ALREADY_CONFIRMED;
        }
        if (request.providerTransactionRef() == null || request.provider() != gateway.provider()) {
            return Settlement.PENDING; // not started with the current provider: nothing to ask
        }
        StatusResult status;
        try {
            status = gateway.status(request.providerTransactionRef());
        } catch (MobileMoneyGateway.GatewayException e) {
            log.warn("Payment {}: provider status unavailable", request.id());
            return Settlement.PROVIDER_UNREACHABLE;
        }
        return apply(request, status);
    }

    Settlement apply(PaymentRequest request, StatusResult status) {
        if (status.status() == ProviderStatus.SUCCESS) {
            boolean enough = status.amount() != null && status.amount() >= request.amountDueXaf();
            boolean sameCurrency = status.currency() == null
                    || request.currency().equalsIgnoreCase(status.currency().trim().toUpperCase(Locale.ROOT));
            if (!enough || !sameCurrency) {
                log.warn("Payment {} not verified: provider amount {} {} for {} {} due", request.id(),
                        status.amount(), status.currency(), request.amountDueXaf(), request.currency());
                return Settlement.NOT_VERIFIED;
            }
            return switch (confirmation.confirm(request.id())) {
                case CONFIRMED -> Settlement.CONFIRMED;
                case ALREADY_CONFIRMED -> Settlement.ALREADY_CONFIRMED;
                case CREDITED -> Settlement.CREDITED;
                case NOT_FOUND -> Settlement.PENDING; // purged meanwhile
            };
        }
        if (status.status() == ProviderStatus.FAILED || status.status() == ProviderStatus.CANCELED) {
            store.markFailed(request.id(), PaymentStatus.IN_FLIGHT, PaymentText.PROVIDER_FAILED,
                    status.status().name());
            return Settlement.FAILED;
        }
        return Settlement.PENDING;
    }
}
