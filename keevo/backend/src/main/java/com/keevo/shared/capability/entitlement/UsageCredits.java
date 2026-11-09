package com.keevo.shared.capability.entitlement;

import java.util.Objects;
import java.util.UUID;

/**
 * Story 19.8 — purchased packs ({@code public.usage_credits}): units of a metric a tenant may consume once its plan's
 * MONTH or LIFETIME quota is used up. Story 21.3 — every credit is journaled ({@code public.usage_credit_movements}:
 * source, payment request, actor) in the same statement as the balance change. Joins the caller's transaction.
 */
public interface UsageCredits {

    /**
     * Adds {@code n} units of {@code metric} to the tenant's pack (upsert) and journals it.
     *
     * @param n     {@code >= 1}
     * @param grant why the units are added (a paid pack, the Bref import)
     * @return the units now remaining
     * @throws org.springframework.dao.DuplicateKeyException when this payment request was already credited (second
     *         guard behind the caller's idempotence: the transaction must roll back)
     */
    long add(UUID tenantId, UsageMetric metric, int n, Grant grant);

    /**
     * @param source           {@link UsagePackSource#PURCHASE} or {@link UsagePackSource#MIGRATION}
     * @param paymentRequestId the confirmed payment of a purchase, {@code null} otherwise
     * @param actorId          who bought (the owner), {@code null} for an import
     */
    record Grant(UsagePackSource source, UUID paymentRequestId, UUID actorId) {

        public Grant {
            Objects.requireNonNull(source, "source");
            if (source == UsagePackSource.CONSUMPTION) {
                throw new IllegalArgumentException("a consumption is journaled by the quota increment");
            }
        }

        /** A pack paid through this payment request by this owner. */
        public static Grant purchase(UUID paymentRequestId, UUID actorId) {
            return new Grant(UsagePackSource.PURCHASE, Objects.requireNonNull(paymentRequestId, "paymentRequestId"),
                    actorId);
        }

        /** Units carried over from Bref (epic 24). */
        public static Grant migration() {
            return new Grant(UsagePackSource.MIGRATION, null, null);
        }
    }
}
