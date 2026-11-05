package com.keevo.shared.module.application;

import com.keevo.identity.api.TenantSaleFlowQueryPort;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.module.adapter.out.persistence.JdbcModuleActivationRuleStore;
import com.keevo.shared.module.application.port.ModuleActivationRuleStore.StoredRuleSet;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story 19.13 — the rule sets seeded by V50 are valid for the shipped manifests: every listed module is installed and
 * owner-toggleable and every set is dependency-closed (each passes the super-admin validation of
 * {@link ModuleActivationRulesService}, saved back unchanged).
 */
@DisplayName("19.13 — default activation rule sets seeded by V50 pass the admin validation")
class DefaultActivationRuleSetsPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_default_rule_sets";

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public").locations("classpath:db/migration")
                .load().migrate();
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @Test
    @DisplayName("POS and DOCUMENT default sets: toggleable, installed, dependency-closed; saved back unchanged")
    void seededSetsAreValid() {
        ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        JdbcModuleActivationRuleStore store = new JdbcModuleActivationRuleStore(dataSource);
        TenantSaleFlowQueryPort noPreferences = schema -> Optional.empty();
        ModuleRegistryService registry = new ModuleRegistryService(catalog, schema -> Optional.empty(),
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
        ModuleActivationRulesService service = new ModuleActivationRulesService(catalog, store, noPreferences,
                registry, new DataSourceTransactionManager(dataSource), Clock.systemUTC());

        List<StoredRuleSet> seeded = store.findAll();
        assertThat(seeded).extracting(StoredRuleSet::saleFlow).containsExactly("POS", "DOCUMENT");
        assertThat(seeded).allMatch(s -> s.sectorType() == null);
        for (StoredRuleSet set : seeded) {
            assertThatCode(() -> service.save(set.saleFlow(), set.sectorType(), set.modules(), null))
                    .as(set.saleFlow()).doesNotThrowAnyException();
        }
        assertThat(store.findAll()).extracting(StoredRuleSet::modules)
                .containsExactly(List.of("invoicing"), List.of("counting", "inventory", "multi-store", "pos"));
    }
}
