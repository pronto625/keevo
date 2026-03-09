package com.keevo.identity.onboarding.adapter.in.web;

/**
 * StoreDto — lightweight store representation for the GET /api/v1/tenant/stores endpoint.
 *
 * <p>Story 2.3 hotfix: Flutter needs a real store UUID to call stock entry/adjust
 * endpoints. This DTO exposes the minimum required fields.
 *
 * <p>Epic 3 will introduce a full StoreRepository — this DTO is intentionally minimal.
 */
public record StoreDto(
        String id,
        String name
) {}
