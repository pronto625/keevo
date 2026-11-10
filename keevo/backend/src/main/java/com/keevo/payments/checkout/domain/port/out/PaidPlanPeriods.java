package com.keevo.payments.checkout.domain.port.out;

import com.keevo.payments.checkout.domain.model.PaidPlanPeriod;

import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.2 (correctif post-test appareil) — read side of the Mobile Money plan payments of 21.1, for the App Store
 * revocation: which paid period the company would be on without the App Store subscription.
 */
public interface PaidPlanPeriods {

    /**
     * The period of the company's latest confirmed plan payment (newest request first; a pack or a request without a
     * period is skipped), empty when it never paid a plan by Mobile Money. One indexed read
     * ({@code ix_payment_requests_tenant_created}), whatever the history.
     */
    Optional<PaidPlanPeriod> latestConfirmed(UUID tenantId);
}
