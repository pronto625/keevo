package com.keevo.admin.subscription.domain.model;

/** PaymentMethod — how an off-platform payment was made (Story 9-2). Labelled in French by the dashboard. */
public enum PaymentMethod {
    MOBILE_MONEY,
    CASH,
    BANK_TRANSFER,
    OTHER,
    /** Story 21.2 — an App Store purchase of the owner (RevenueCat event), never entered by the super-admin. */
    APP_STORE,
    /** Story 24.5 — a plan the owner paid entirely with the account credit (amount 0), never entered by hand. */
    ACCOUNT_CREDIT
}
