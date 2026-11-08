package com.keevo.admin.dashboard.domain.port.out;

import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityItem;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.DocumentFigures;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.StoreFigures;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Story 20.13 — platform figures of the super-admin dashboard: public tables in one query each, tenant tables in one
 * bounded {@code UNION ALL} statement each (never one query per tenant). Months are {@code yyyy-MM}, Africa/Douala.
 */
public interface PlatformDashboardReadPort {

    /** Non-super-admin users: total, active, created in [monthStart, now), created in [previousMonthStart, monthStart). */
    record UserCounts(long total, long active, long newThisMonth, long newLastMonth) {
    }

    /** Live accounts (not deleted, not KV-ADMIN): total, ACTIVE, SUSPENDED, created this / last month. */
    record AccountCounts(long total, long active, long suspended, long newThisMonth, long newLastMonth) {
    }

    /** Every live tenant (one query). */
    List<TenantBranch> liveTenants();

    UserCounts userCounts(Instant monthStart, Instant previousMonthStart);

    AccountCounts accountCounts(Instant monthStart, Instant previousMonthStart);

    StoreFigures storeFigures(List<TenantBranch> tenants, Instant monthStart);

    DocumentFigures documentFigures(List<TenantBranch> tenants);

    Map<String, Long> newUsersByMonth(Instant from, Instant to);

    Map<String, Long> newAccountsByMonth(Instant from, Instant to);

    Map<String, Long> newStoresByMonth(List<TenantBranch> tenants, Instant from, Instant to);

    /** Counted invoices (20.9 predicate) by civil issue month. */
    Map<String, Long> invoicesByMonth(List<TenantBranch> tenants, Instant from, Instant to);

    /** Active stores per tenant id. */
    Map<String, Long> storeCounts(List<TenantBranch> tenants);

    /** Owner display name per tenant id (first OWNER membership with a name). */
    Map<String, String> ownerNames(Collection<String> tenantIds);

    /** The {@code limit} most recent platform events, newest first. */
    List<ActivityItem> activity(List<TenantBranch> tenants, int limit);
}
