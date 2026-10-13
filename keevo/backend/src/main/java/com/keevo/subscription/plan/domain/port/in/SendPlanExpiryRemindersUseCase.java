package com.keevo.subscription.plan.domain.port.in;

/**
 * SendPlanExpiryRemindersUseCase — daily WhatsApp reminder to owners whose PREMIUM / PREMIUM_TRIAL plan expires within
 * 7 calendar days (WAT; thresholds 7 and 1), at most once per (tenant, threshold, expiry date) — Story 9-2.
 */
public interface SendPlanExpiryRemindersUseCase {

    /** @return number of reminders sent by this run */
    int execute();
}
