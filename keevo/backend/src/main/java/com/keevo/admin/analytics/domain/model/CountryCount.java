package com.keevo.admin.analytics.domain.model;

/**
 * Tenants per country (Story 9-4), derived from the owner's phone calling code (V1 proxy: stores have
 * no structured city / country).
 *
 * @param country ISO 3166-1 alpha-2 code ({@code CM}, {@code CI}…), {@code OTHER} for an unknown
 *                calling code, {@code UNKNOWN} when the tenant has no owner phone
 */
public record CountryCount(String country, long tenantCount) {}
