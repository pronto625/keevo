package com.keevo.shared.module;

/**
 * Provisioning refused: no live ({@code status <> 'DELETED'}) {@code public.tenants} row has the given schema
 * (Story 13.4). A batch counts the tenant as skipped.
 */
public class TenantNotFoundException extends IllegalStateException {

    public TenantNotFoundException() {
        super("No live tenant for the given schema");
    }
}
