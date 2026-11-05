package com.keevo.identity.onboarding.adapter.in.web;

/**
 * Story 19.12 — {@code PUT /api/v1/tenant/sale-flow} body: {@code {"defaultSaleFlow": "POS" | "DOCUMENT"}}. Kept a
 * string so any other value (or null) answers a French {@code VALIDATION_ERROR}, not a parse error.
 */
public record UpdateSaleFlowRequestDto(String defaultSaleFlow) {
}
