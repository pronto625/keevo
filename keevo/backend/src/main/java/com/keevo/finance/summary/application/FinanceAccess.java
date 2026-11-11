package com.keevo.finance.summary.application;

import java.util.UUID;

/**
 * Story 20.11 — who reads the finance summary, read once by the REST adapter from the authenticated actor (the use case
 * never reads the security context). Same store rule as the 20.10 expenses: an OWNER sees every store; a member whose
 * owner-defined role grants {@code FINANCE_VIEW} (19.14, reprise 11/11/2026) their assigned store only — totals,
 * series, comparison and categories computed on that store, no {@code byStore}, a {@code storeId=} filter ignored —
 * and nothing without store.
 */
public record FinanceAccess(boolean owner, UUID assignedStoreId) {

    public static final FinanceAccess OWNER = new FinanceAccess(true, null);

    public static FinanceAccess member(UUID storeId) {
        return new FinanceAccess(false, storeId);
    }
}
