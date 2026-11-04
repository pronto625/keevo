package com.keevo.shared.capability.entitlement;

/**
 * Story 19.8 — what a tenant consumes and a plan may cap ({@code public.plan_quotas.metric},
 * {@code public.usage_counters.metric}). A code concept: each metric has a consumer in the code (invoice issue,
 * SMS / email senders of 19.9, AI stories). The limits are data ({@link PlanQuota}), never constants.
 */
public enum UsageMetric {
    /** Documents issued by a user (an invoice at issue); automatic bridge documents and credit notes never count. */
    DOCUMENTS("Documents"),
    /** SMS sent (counted on provider success, 19.9). */
    SMS("SMS"),
    /** Emails sent (counted on provider success, 19.9). */
    EMAILS("Emails"),
    /** AI credits consumed. */
    AI_CREDITS("Crédits IA");

    private final String label;

    UsageMetric(String label) {
        this.label = label;
    }

    /** French name shown to people (the code stays the API value). */
    public String label() {
        return label;
    }
}
