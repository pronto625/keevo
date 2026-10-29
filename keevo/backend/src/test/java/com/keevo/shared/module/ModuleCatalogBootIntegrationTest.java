package com.keevo.shared.module;

import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
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

    @Test
    void bootSeedsTheTenModulesAndARestartChangesNothing() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForList("SELECT id FROM public.module_definitions ORDER BY id", String.class))
                .contains("admin", "catalog", "core", "crm", "inventory", "messaging", "pos", "reporting", "store", "sync");
        assertThat(jdbc.queryForObject("SELECT category FROM public.module_definitions WHERE id = 'sync'", String.class))
                .isEqualTo("core");

        assertThat(seeder.seed(ModuleManifestLoader.loadFromClasspath(resources)).changedRows()).isZero();
        assertThat(TenantSchemaSyncService.globalOnlyTables()).contains("module_definitions", "tenant_modules");
    }
}
