package com.keevo.reporting.export.application.service;

import com.keevo.reporting.export.SyntheticExportProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExportDatasetRegistry (v1s-18-1)")
class ExportDatasetRegistryTest {

    @Test
    @DisplayName("keys not matching [a-z][a-z0-9_]{0,39} fail the start")
    void badKeysRejected() {
        for (String bad : List.of("Stores", "1stores", "sto,res", "../x", "a b", "x".repeat(41), "")) {
            assertThatThrownBy(() -> new ExportDatasetRegistry(List.of(new SyntheticExportProvider(bad, "L", 1))))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid export dataset key");
        }
        assertThat(new ExportDatasetRegistry(List.of(new SyntheticExportProvider("stock_levels_2", "L", 1)))
                .find("stock_levels_2")).isPresent();
        assertThat(new ExportDatasetRegistry(List.of(new SyntheticExportProvider("x".repeat(40), "L", 1)))
                .all()).hasSize(1);
    }

    @Test
    @DisplayName("duplicate key fails the start")
    void duplicateRejected() {
        assertThatThrownBy(() -> new ExportDatasetRegistry(List.of(
                new SyntheticExportProvider("stores", "A", 1), new SyntheticExportProvider("stores", "B", 1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }
}
