package com.keevo.finance.module;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code finance} module — « Finance » (Story 20.11, Bref finance summary in Keevo, FUS-FR26): the result of a
 * period (revenue excl. VAT − expenses), the margin and their trend.
 *
 * <p>Manifest: {@code keevo/modules/finance/module.json} (premium, requires {@code core}, {@code store}, {@code expenses} and
 * {@code reporting}). No table: it reads the revenue source of 20.9 and the expense totals of the expenses module.
 * The offer (which plans include it) is data: public migration V58.
 *
 * <p>Permission {@code FINANCE_VIEW}: held by {@code OWNER} only (Bref keeps the margins to the owner) — no role of
 * {@code rolePermissions} receives it.
 */
@Component
public class FinanceModule implements KeevoModule {

    public static final String ID = "finance";

    public static final String FINANCE_VIEW = "FINANCE_VIEW";

    private static final Logger log = LoggerFactory.getLogger(FinanceModule.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module finance activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module finance deactivated for tenant {} (nothing stored)", ctx.tenantCode());
    }
}
