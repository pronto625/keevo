package com.keevo.finance.summary.application;

import java.util.UUID;

/**
 * Story 20.11 — who reads the finance summary, read once by the REST adapter from the authenticated actor (the use case
 * never reads the security context). Same store rule as the 20.10 expenses: an OWNER sees every store; anyone else
 * (should a role ever receive {@code FINANCE_VIEW}) their assigned store only, nothing without store.
 */
public record FinanceAccess(boolean owner, UUID assignedStoreId) {

    public static final FinanceAccess OWNER = new FinanceAccess(true, null);

    public static FinanceAccess member(UUID storeId) {
        return new FinanceAccess(false, storeId);
    }
}
