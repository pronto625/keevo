/**
 * Story 20.13 — the SQL of the single revenue source (20.9), published as the named interface {@code revenue} for
 * its only reader outside reporting: the platform revenue adapter of the super-admin API
 * ({@code admin.platformrevenue.adapter.out.RevenueSourcePlatformRevenueAdapter}), which runs the same relations over
 * every tenant schema in one statement. 20-14 moves the source to {@code shared.capability.revenue}; this file then
 * goes away with it.
 */
@org.springframework.modulith.NamedInterface("revenue")
package com.keevo.reporting.revenue.adapter.out.persistence;
