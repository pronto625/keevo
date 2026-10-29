package com.keevo.shared.module;

/**
 * Provisioning refused: the tenant has no {@code tenant_modules} row for the module and the module is not active
 * through the free rule (Story 13.4). A batch counts the tenant as skipped.
 */
public class ModuleNotActiveException extends IllegalStateException {

    public ModuleNotActiveException(String moduleId) {
        super("Module '" + moduleId + "' is not active for the tenant");
    }
}
