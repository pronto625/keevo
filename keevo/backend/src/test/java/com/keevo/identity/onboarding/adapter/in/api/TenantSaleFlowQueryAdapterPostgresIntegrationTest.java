package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.NavigationPreset;
import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowQueryPort.TenantSaleFlowView;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.13 — {@link TenantSaleFlowQueryAdapter} on PostgreSQL: the tenant's current preferences row (latest
 * {@code created_at}) on the explicit schema; no row (onboarding not done) → empty.
 */
@DisplayName("19.13 — TenantSaleFlowQueryAdapter (PostgreSQL)")
class TenantSaleFlowQueryAdapterPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_sale_flow_query";
    private static final String SCHEMA = "kv_sfq001";

    private static JdbcTemplate jdbc;
    private static TenantSaleFlowQueryAdapter adapter;

    @BeforeAll
    static void createSchema() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password()));
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        jdbc.execute("CREATE TABLE " + SCHEMA + ".tenant_preferences (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), "
                + "sector_type VARCHAR(30), default_sale_flow VARCHAR(10) NOT NULL DEFAULT 'POS', "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        adapter = new TenantSaleFlowQueryAdapter(jdbc);
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @Test
    @DisplayName("no preferences row → empty; DOCUMENT row → DOCUMENT, sector, preset DOCUMENT; latest row wins")
    void find() {
        assertThat(adapter.find(SCHEMA)).isEmpty();

        jdbc.update("INSERT INTO " + SCHEMA + ".tenant_preferences (sector_type, default_sale_flow, created_at) "
                + "VALUES ('CLOTHING', 'POS', now() - interval '1 day')");
        jdbc.update("INSERT INTO " + SCHEMA + ".tenant_preferences (sector_type, default_sale_flow) "
                + "VALUES ('PHARMACY', 'DOCUMENT')");

        Optional<TenantSaleFlowView> view = adapter.find(SCHEMA);
        assertThat(view).isPresent();
        assertThat(view.get().saleFlow()).isEqualTo(SaleFlow.DOCUMENT);
        assertThat(view.get().sectorType()).isEqualTo("PHARMACY");
        assertThat(view.get().navigationPreset()).isEqualTo(NavigationPreset.DOCUMENT);
    }
}
