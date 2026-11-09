package com.keevo.payments.appstore.domain.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Story 21.2 — read access to RevenueCat (REST API v2): the subscriptions of a customer (app_user_id = Keevo user id).
 * Strategy chosen by configuration: the RevenueCat REST client, a disabled client (no keys) or an in-memory fake
 * (dev/test profiles only). Never called inside a database transaction.
 */
public interface RevenueCatClient {

    /** Whether calls can be made (keys configured). */
    boolean enabled();

    /**
     * Every subscription RevenueCat knows for the customer.
     *
     * @throws RevenueCatUnavailableException when disabled, unreachable or answering an error (never carries a secret)
     */
    List<StoreSubscription> subscriptions(String appUserId);

    /**
     * @param originalTransactionId the Apple original transaction id ({@code store_subscription_identifier})
     * @param productId             the App Store product identifier ({@code store_identifier})
     * @param store                 upper-case store ({@code APP_STORE}, {@code PLAY_STORE}…)
     * @param givesAccess           RevenueCat says the subscription grants access now (grace included)
     * @param expiresAt             end of the current period, {@code null} when unknown
     * @param sandbox               environment sandbox
     * @param autoRenew             Apple will renew ({@code auto_renewal_status = will_renew})
     * @param inGrace               in Apple's grace period / billing retry (a billing issue)
     */
    record StoreSubscription(String originalTransactionId, String productId, String store, boolean givesAccess,
                             Instant expiresAt, boolean sandbox, boolean autoRenew, boolean inGrace) {

        /** Renewing, no billing issue. */
        public StoreSubscription(String originalTransactionId, String productId, String store, boolean givesAccess,
                                 Instant expiresAt, boolean sandbox) {
            this(originalTransactionId, productId, store, givesAccess, expiresAt, sandbox, true, false);
        }
    }

    /** RevenueCat could not answer (or the client is disabled). The message never contains a key or a payload. */
    class RevenueCatUnavailableException extends RuntimeException {
        private final boolean disabled;

        public RevenueCatUnavailableException(String message, boolean disabled) {
            super(message);
            this.disabled = disabled;
        }

        public boolean disabled() {
            return disabled;
        }
    }
}
