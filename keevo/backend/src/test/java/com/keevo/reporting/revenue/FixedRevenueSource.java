package com.keevo.reporting.revenue;

import com.keevo.reporting.revenue.adapter.out.persistence.RevenueRelations;
import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort;

/**
 * Story 20.9 — test double of the revenue source with fixed branches (no catalogue lookup): {@link #POS_ONLY} is the
 * relation of a V1 Keevo tenant (sales only); {@link #ALL} adds invoices and credit notes; {@link #DOCUMENTS_ONLY}
 * (tests only) leaves sales out. Store-scoped relations are always sales only, as in production.
 */
public record FixedRevenueSource(boolean sales, boolean invoicing) implements RevenueQueryPort {

    public static final FixedRevenueSource POS_ONLY = new FixedRevenueSource(true, false);
    public static final FixedRevenueSource DOCUMENTS_ONLY = new FixedRevenueSource(false, true);
    public static final FixedRevenueSource ALL = new FixedRevenueSource(true, true);

    @Override
    public Relations current() {
        return new Relations(RevenueRelations.documents(sales, invoicing), RevenueRelations.lines(sales, invoicing));
    }

    @Override
    public Relations storeScoped() {
        return new Relations(RevenueRelations.POS_DOCUMENTS, RevenueRelations.POS_LINES);
    }
}
