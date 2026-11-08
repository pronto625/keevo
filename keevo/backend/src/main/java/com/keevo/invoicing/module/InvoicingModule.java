package com.keevo.invoicing.module;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


/**
 * The {@code invoicing} module — « Facturation » (Story 14.1, first paid module built on the socle; MOD-FR7, MOD-FR17).
 *
 * <p>Manifest: {@code keevo/modules/invoicing/module.json} (requires {@code core} only — works with pos, crm and
 * catalog disabled). Tables: {@code db/modules/invoicing/V1__invoicing.sql} ({@code invoicing_settings},
 * {@code invoices}, {@code invoice_items}, {@code invoice_counters}; history {@code flyway_history_invoicing}), created
 * only in the schemas of tenants that use the module (registration trial, activation, or first gated call), reached
 * through JDBC adapters — no JPA entity, nothing in {@code public}.
 *
 * <p>Permissions, declared by the manifest ({@code rolePermissions}, Story 19.5): {@code OWNER} holds all
 * four; {@code MANAGER} and {@code EMPLOYEE} may view, create and issue / record payment; {@code VIEWER} may only view;
 * cancelling and the seller settings ({@code INVOICE_MANAGE}) are owner decisions.
 *
 * <p>Story 20.7: one table outside the tenant schema — {@code public.invoice_legacy_links} (public V57), the
 * cross-tenant index of the old Bref public links of imported invoices (one primary-key read per opened link).
 */
@Component
public class InvoicingModule implements KeevoModule {

    public static final String ID = "invoicing";

    public static final String INVOICE_CREATE = "INVOICE_CREATE";
    public static final String INVOICE_VIEW = "INVOICE_VIEW";
    public static final String INVOICE_SEND = "INVOICE_SEND";
    public static final String INVOICE_MANAGE = "INVOICE_MANAGE";

    private static final Logger log = LoggerFactory.getLogger(InvoicingModule.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module invoicing activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module invoicing deactivated for tenant {} (invoices kept)", ctx.tenantCode());
    }
}
