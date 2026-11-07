package com.keevo.expenses.module;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code expenses} module — « Dépenses » (Story 20.10, Bref expenses in Keevo, FUS-FR25).
 *
 * <p>Manifest: {@code keevo/modules/expenses/module.json} (premium, requires {@code core} and {@code store}). Table:
 * {@code db/modules/expenses/V1__expenses.sql} ({@code expenses}; history {@code flyway_history_expenses}), created only
 * in the schemas of tenants that use the module, reached through a JDBC adapter — no JPA entity, nothing in
 * {@code public}. The offer (which plans include it) is data: public migration V56.
 *
 * <p>Permissions ({@code rolePermissions}): {@code OWNER} holds all three; {@code MANAGER} views, records, edits and
 * deletes; {@code EMPLOYEE} views, records and edits (their store only); {@code VIEWER} views only.
 */
@Component
public class ExpensesModule implements KeevoModule {

    public static final String ID = "expenses";

    public static final String EXPENSE_VIEW = "EXPENSE_VIEW";
    public static final String EXPENSE_CREATE = "EXPENSE_CREATE";
    public static final String EXPENSE_DELETE = "EXPENSE_DELETE";

    private static final Logger log = LoggerFactory.getLogger(ExpensesModule.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module expenses activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module expenses deactivated for tenant {} (expenses kept)", ctx.tenantCode());
    }
}
