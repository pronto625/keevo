/**
 * Story 20.13 — the SQL of the single revenue source (20.9, extended by 20-14 / 20-11), published as the named
 * interface {@code revenue} for its only reader outside reporting: the platform revenue adapter of the super-admin API
 * ({@code admin.platformrevenue.adapter.out.RevenueSourcePlatformRevenueAdapter}), which runs the same relations over
 * every tenant schema. The port ({@code shared.capability.revenue.RevenueQueryPort}) is bound to the current tenant
 * and cannot hand the relations of other schemas, so the SQL itself is published here.
 */
@org.springframework.modulith.NamedInterface("revenue")
package com.keevo.reporting.revenue.adapter.out.persistence;
