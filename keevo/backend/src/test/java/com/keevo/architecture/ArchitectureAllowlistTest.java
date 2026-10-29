package com.keevo.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ratchet allow-list parsing and comparison (Story 12.6b). */
class ArchitectureAllowlistTest {

    @Test
    void methodKeyWithParametersParses() throws IOException {
        assertThat(ArchitectureAllowlist.parse(new StringReader(
            "# header\n\n  com.x.Cls#m(SyncOperation,UUID)   # runs in a tx  \n")))
            .containsExactly(Map.entry("com.x.Cls#m(SyncOperation,UUID)", "runs in a tx"));
    }

    @Test
    void utf8BomIsStripped() throws IOException {
        assertThat(ArchitectureAllowlist.parse(new StringReader("﻿com.x.Cls   # r\n"))).containsOnlyKeys("com.x.Cls");
    }

    @Test
    void missingReasonFails() {
        assertThatThrownBy(() -> ArchitectureAllowlist.parse(new StringReader("com.x.Cls\n")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("without a reason");
        assertThatThrownBy(() -> ArchitectureAllowlist.parse(new StringReader("com.x.Cls   #   \n")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("without a reason");
    }

    @Test
    void duplicateKeyFails() {
        assertThatThrownBy(() -> ArchitectureAllowlist.parse(new StringReader("com.x.A   # r1\ncom.x.A   # r2\n")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate key com.x.A");
    }

    @Test
    void unexpectedAndStaleAreReported() {
        Map<String, String> violations = Map.of("com.x.New", "detail", "com.x.Known", "detail");
        Map<String, String> allowlist = Map.of("com.x.Known", "r", "com.x.Gone", "r");

        assertThat(ArchitectureAllowlist.unexpected(violations, allowlist)).containsExactly("com.x.New — detail");
        assertThat(ArchitectureAllowlist.stale(violations, allowlist)).containsExactly("com.x.Gone");
    }
}
