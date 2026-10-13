package com.keevo.admin.notification.domain.model;

/**
 * Audience of a global push (Story 9-5, FR82): every active company, companies of one current plan
 * ({@code public.tenants.plan_type}), or a single company.
 */
public enum GlobalNotificationTarget {
    ALL(null),
    PREMIUM("PREMIUM"),
    TRIAL("PREMIUM_TRIAL"),
    FREE("FREE"),
    TENANT(null);

    private final String planType;

    GlobalNotificationTarget(String planType) {
        this.planType = planType;
    }

    /** Plan code of {@code public.tenants.plan_type} this target filters on, or null. */
    public String planType() {
        return planType;
    }
}
