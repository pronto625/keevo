package com.keevo.payments.checkout.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/** Story 21.1 — body of {@code POST /api/v1/payments/{id}/authorize}: the code received by SMS (alias {@code otp}). */
public record AuthorizeRequest(@JsonAlias("otp") String code) {
}
