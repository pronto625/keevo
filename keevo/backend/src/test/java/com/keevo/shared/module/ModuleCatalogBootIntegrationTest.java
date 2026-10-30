package com.keevo.shared.module;

import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleCatalogSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 13.1 — the real boot path: the application context seeds the catalogue, a re-run changes nothing. */
class ModuleCatalogBootIntegrationTest extends FullContextIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ModuleCatalogSeeder seeder;

    @Autowired
    private ResourcePatternResolver resources;

    @Autowired
    private ModuleCatalog catalog;

    @Autowired
    private ModuleRegistry registry;

    @Test
    void bootSeedsTheTenModulesAndARestartChangesNothing() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForList("SELECT id FROM public.module_definitions ORDER BY id", String.class))
                .contains("admin", "catalog", "core", "crm", "inventory", "messaging", "pos", "reporting", "store", "sync",
                        "multi-store", "counting", "advanced-reports", "report-config", "alerts", "profitability",
                        "exports");
        assertThat(jdbc.queryForObject("SELECT category FROM public.module_definitions WHERE id = 'sync'", String.class))
                .isEqualTo("core");

        assertThat(seeder.seed(ModuleManifestLoader.loadFromClasspath(resources)).changedRows()).isZero();
        // 13.2: the context holds the validated set once; it is the one the seed used
        assertThat(catalog.manifests()).hasSize(17);
        assertThat(seeder.seed(catalog.loaded()).changedRows()).isZero();
        // 13.2: the registry is wired on the same set and works outside a request (no request-scope proxy)
        assertThat(registry.installed()).hasSize(17);
        assertThat(registry.activeModules("kv_nobody")).containsExactly("core");
        assertThat(TenantSchemaSyncService.globalOnlyTables()).contains("module_definitions", "tenant_modules");
    }
}
