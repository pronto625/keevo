package com.keevo.expenses.expense.adapter.in;

import com.keevo.expenses.expense.application.service.ExpenseAccess;
import com.keevo.shared.infrastructure.security.CurrentActor;

/**
 * Story 20.10 — the expense access of the authenticated actor, read once by the inbound adapters (REST, sync): an
 * OWNER sees every store; anyone else is pinned to their assigned store (fail closed: no store = nothing).
 */
public final class CurrentExpenseAccess {

    private CurrentExpenseAccess() {
    }

    public static ExpenseAccess get() {
        if (CurrentActor.isOwner()) {
            return ExpenseAccess.OWNER;
        }
        return ExpenseAccess.member(CurrentActor.assignedStoreIdOrNull());
    }
}
