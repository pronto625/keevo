package com.keevo.reporting.revenue;

import com.keevo.reporting.revenue.adapter.out.persistence.RevenueRelations;
import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort;

/**
 * Story 20.9 — test double of the revenue source with fixed branches (no module registry): {@link #POS_ONLY} is the
 * relation of a V1 Keevo tenant (sales only); {@link #ALL} adds invoices and credit notes.
 */
public record FixedRevenueSource(boolean sales, boolean invoicing) implements RevenueQueryPort {

    public static final FixedRevenueSource POS_ONLY = new FixedRevenueSource(true, false);
    public static final FixedRevenueSource DOCUMENTS_ONLY = new FixedRevenueSource(false, true);
    public static final FixedRevenueSource ALL = new FixedRevenueSource(true, true);

    @Override
    public String documents() {
        return RevenueRelations.documents(sales, invoicing);
    }

    @Override
    public String lines() {
        return RevenueRelations.lines(sales, invoicing);
    }
}
