package com.keevo.admin.platformrevenue.domain.port.out;

import com.keevo.admin.crosstenant.TenantBranch;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Story 20.13 (R4, routed by 20.9) — merchant revenue across tenant schemas, read from the single revenue source of
 * 20.9: COMPLETED sales ∪ counted invoices (a POS sale bridged to an invoice counts once) − credit notes. Never
 * reimplements the union, never one query per tenant.
 */
public interface PlatformRevenuePort {

    /** Grouping of the totals. Day and month are Africa/Douala civil dates of the revenue instant. */
    enum Bucket {
        /** key = tenant id */
        TENANT,
        /** key = {@code yyyy-MM-dd} */
        DAY,
        /** key = {@code yyyy-MM} */
        MONTH,
        /** key = seller user id ({@code employee_id} of the revenue document — the sale's actor, the invoice's
         *  creator); documents without seller are left out. Totals of one person across accounts are added up. */
        SELLER
    }

    /** Revenue (credit notes deducted) and number of revenue documents (sales + counted invoices). */
    record Totals(long revenue, long documents) {
        public static final Totals ZERO = new Totals(0, 0);

        public Totals plus(Totals other) {
            return new Totals(revenue + other.revenue, documents + other.documents);
        }
    }

    /**
     * Totals per bucket over {@code [from, to)} ({@code null} bound = open) for the given tenants; buckets without
     * revenue are absent.
     */
    Map<String, Totals> totals(List<TenantBranch> tenants, Instant from, Instant to, Bucket bucket);
}
