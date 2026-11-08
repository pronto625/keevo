package com.keevo.expenses.expense.application.service;

import java.util.UUID;

/**
 * Story 20.10 — who acts on expenses, read by the inbound adapters (REST, sync) from the authenticated actor; the use
 * cases never read the security context. Only the OWNER and the MANAGER reach the use cases (user decision
 * 2026-11-08: the {@code EXPENSE_*} permissions are held by no EMPLOYEE or VIEWER); a member value is a MANAGER.
 *
 * @param owner           OWNER — every store (may pick or change an expense's store)
 * @param assignedStoreId the store of a store member (MANAGER, EMPLOYEE, VIEWER); {@code null} for an OWNER, or for a
 *                        member without store (who then sees nothing and cannot record)
 */
public record ExpenseAccess(boolean owner, UUID assignedStoreId) {

    public static final ExpenseAccess OWNER = new ExpenseAccess(true, null);

    public static ExpenseAccess member(UUID storeId) {
        return new ExpenseAccess(false, storeId);
    }

    /** Whether this actor sees the expenses of {@code storeId}. */
    public boolean sees(UUID storeId) {
        return owner || (assignedStoreId != null && assignedStoreId.equals(storeId));
    }
}
