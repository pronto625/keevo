package com.keevo.reporting.module;

import com.keevo.shared.module.KeevoModule;
import org.springframework.stereotype.Component;

/**
 * The {@code reporting} module — « Rapports » (Story 13.9). Manifest: {@code keevo/modules/reporting/module.json}. No
 * capability, no migration of its own yet. Its event listeners are {@code @ModuleScoped("reporting")}: they consume
 * events only for tenants where the module is not DISABLED (READ_ONLY still consumes — reports read past data).
 */
@Component
public class ReportingModule implements KeevoModule {

    public static final String ID = "reporting";

    @Override
    public String id() {
        return ID;
    }
}
