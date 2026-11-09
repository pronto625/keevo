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
 */
public record CheckoutRequest(@JsonAlias("plan") String planCode,
                              @JsonAlias({"period", "cycle"}) String cycleCode,
                              String promoCode,
                              @JsonAlias("paymentMethod") String method,
                              @JsonAlias("phone") String phoneNumber) {
}
