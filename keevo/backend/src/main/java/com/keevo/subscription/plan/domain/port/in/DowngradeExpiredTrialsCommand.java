package com.keevo.subscription.plan.domain.port.in;

/**
 * DowngradeExpiredTrialsCommand — Input record for the scheduled trial auto-downgrade.
 */
public record DowngradeExpiredTrialsCommand(String triggeredBy) {}
