package com.keevo.shared.module.adapter.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 13.4 — id hygiene, history table naming and the dedicated (non-pooled) DataSource of the module Flyway. */
class FlywayModuleMigrationsTest {

    private final FlywayModuleMigrations migrations =
            new FlywayModuleMigrations(new DriverManagerDataSource("jdbc:postgresql://unused/db"));

    @Test
    @DisplayName("hyphenated id → underscores in the history table")
    void hyphenatedId() {
        assertThat(FlywayModuleMigrations.historyTable("a-b")).isEqualTo("flyway_history_a_b");
        assertThat(FlywayModuleMigrations.historyTable("stock-count-v2")).isEqualTo("flyway_history_stock_count_v2");
    }

    @Test
    @DisplayName("length boundary: history name of 63 chars OK, 64 → IAE (id still valid for the manifest)")
    void lengthBoundary() {
        String id48 = "a".repeat(48);
        assertThat(FlywayModuleMigrations.historyTable(id48)).hasSize(63);
        String id49 = "a".repeat(49);
        assertThatThrownBy(() -> FlywayModuleMigrations.historyTable(id49))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("length=49");
    }

    @Test
    @DisplayName("ids outside the manifest rule are refused before any interpolation, raw value never echoed")
    void invalidIds() {
        for (String bad : List.of("", "A", "a_b", "../etc", "a--b", "-a", "a b", "a".repeat(51), "x*")) {
            assertThatThrownBy(() -> migrations.hasMigrations(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Invalid module id (length=");
            assertThatThrownBy(() -> migrations.migrate("kv_abc123", bad))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> FlywayModuleMigrations.historyTable(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> migrations.hasMigrations(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid module id (length=null)");
        assertThatThrownBy(() -> migrations.hasMigrations("../etc")).hasMessageNotContaining("etc");
    }

    @Test
    @DisplayName("invalid schema refused before Flyway")
    void invalidSchema() {
        assertThatThrownBy(() -> migrations.migrate("public", "provtest")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Flyway naming filter: versioned / repeatable only")
    void namingFilter() {
        assertThat(FlywayModuleMigrations.FLYWAY_SCRIPT.matcher("V1__init.sql").matches()).isTrue();
        assertThat(FlywayModuleMigrations.FLYWAY_SCRIPT.matcher("V2.1__more.sql").matches()).isTrue();
        assertThat(FlywayModuleMigrations.FLYWAY_SCRIPT.matcher("V2_1__more.sql").matches()).isTrue();
        assertThat(FlywayModuleMigrations.FLYWAY_SCRIPT.matcher("R__views.sql").matches()).isTrue();
        assertThat(FlywayModuleMigrations.FLYWAY_SCRIPT.matcher("V1_init.sql").matches()).isFalse();
        assertThat(FlywayModuleMigrations.FLYWAY_SCRIPT.matcher("notes.sql").matches()).isFalse();
        assertThat(FlywayModuleMigrations.FLYWAY_SCRIPT.matcher("U1__undo.sql").matches()).isFalse();
    }

    @Test
    @DisplayName("Flyway never gets the pool: a Hikari pool is replaced by a DriverManagerDataSource, others refused")
    void dedicatedDataSource() throws SQLException {
        try (HikariDataSource pool = new HikariDataSource()) { // lazy: no connection opened
            pool.setJdbcUrl("jdbc:postgresql://db.example/keevo");
            pool.setUsername("keevo");
            pool.setPassword("secret");
            DataSource dedicated = new FlywayModuleMigrations(pool).dedicatedDataSource();
            assertThat(dedicated).isInstanceOf(DriverManagerDataSource.class).isNotSameAs(pool);
            DriverManagerDataSource dm = (DriverManagerDataSource) dedicated;
            assertThat(dm.getUrl()).isEqualTo("jdbc:postgresql://db.example/keevo");
            assertThat(dm.getUsername()).isEqualTo("keevo");
        }
        DriverManagerDataSource plain = new DriverManagerDataSource("jdbc:postgresql://unused/db");
        assertThat(new FlywayModuleMigrations(plain).dedicatedDataSource()).isSameAs(plain);
        // SingleConnectionDataSource is driver-based too, but is a "pool of one": refused
        SingleConnectionDataSource single = new SingleConnectionDataSource("jdbc:postgresql://unused/db", true);
        assertThatThrownBy(() -> new FlywayModuleMigrations(single).dedicatedDataSource())
                .isInstanceOf(IllegalStateException.class);
    }
}
