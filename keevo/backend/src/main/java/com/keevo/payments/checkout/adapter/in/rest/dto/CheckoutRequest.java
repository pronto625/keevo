package com.keevo.payments.checkout.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Story 21.1 — body of {@code POST /api/v1/payments/checkout}.
 *
 * @param planCode    plan bought (alias {@code plan})
 * @param cycleCode   billing cycle (aliases {@code period}, {@code cycle})
 * @param promoCode   optional, case-insensitive
 * @param method      {@code ORANGE_MONEY | MTN_MOMO} (Bref's {@code orange_money} / {@code momo} accepted); not
 *                    required for a free offer
 * @param phoneNumber Mobile Money number ({@code 677000111}, {@code +237677000111}); not required for a free offer
 * @param packCode    Story 21.3 — usage pack bought instead of a plan (alias {@code pack})
 * @param useCredit   Story 21.3 — apply the account credit first (default false)
 */
public record CheckoutRequest(@JsonAlias("plan") String planCode,
                              @JsonAlias({"period", "cycle"}) String cycleCode,
                              String promoCode,
                              @JsonAlias("paymentMethod") String method,
                              @JsonAlias("phone") String phoneNumber,
                              @JsonAlias("pack") String packCode,
                              Boolean useCredit) {

    /** A plan checkout (Story 21.1). */
    public CheckoutRequest(String planCode, String cycleCode, String promoCode, String method, String phoneNumber) {
        this(planCode, cycleCode, promoCode, method, phoneNumber, null, null);
    }
}
