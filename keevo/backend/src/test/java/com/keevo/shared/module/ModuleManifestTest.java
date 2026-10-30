package com.keevo.shared.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 13.9 — {@link ModuleManifest}'s compact constructor enforces its own invariants. */
class ModuleManifestTest {

    private static ModuleManifest manifest(String id, String displayName, List<String> requires) {
        return new ModuleManifest(id, "1.0.0", displayName, ModuleCategory.BUSINESS, requires, List.of(), List.of(),
                List.of(), List.of(), List.of(), null, List.of(), List.of(), "module." + id, true);
    }

    @Test
    @DisplayName("a valid hand-built manifest: lists copied and immutable, no extension points → NONE")
    void valid() {
        List<String> requires = new ArrayList<>(List.of("core"));
        ModuleManifest manifest = manifest("my-module", "Mon module", requires);
        requires.add("pos");
        assertThat(manifest.requires()).containsExactly("core");
        assertThatThrownBy(() -> manifest.requires().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(manifest.extensionPoints()).isEqualTo(ModuleManifest.ExtensionPoints.NONE);
        assertThat(manifest("a".repeat(ModuleManifestLoader.ID_MAX), "X", List.of()).id()).hasSize(48);
    }

    @Test
    @DisplayName("invalid id (pattern or longer than ID_MAX = 48) → IllegalArgumentException")
    void invalidId() {
        for (String bad : List.of("", "Pos", "pos_x", "pos-", "a--b", "1pos", "a".repeat(49))) {
            assertThatThrownBy(() -> manifest(bad, "X", List.of()))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ModuleManifestLoader.ID_MAX).isEqualTo(48);
    }

    @Test
    @DisplayName("blank display name → IllegalArgumentException; missing component → NullPointerException")
    void blankDisplayNameAndNulls() {
        assertThatThrownBy(() -> manifest("crm", "  ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manifest(null, "X", List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manifest("crm", null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a null list or a null entry → IllegalArgumentException naming the field")
    void nullLists() {
        assertThatThrownBy(() -> manifest("crm", "X", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'requires'");
        assertThatThrownBy(() -> manifest("crm", "X", Arrays.asList("core", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'requires'");
        assertThatThrownBy(() -> new ModuleManifest.ExtensionPoints(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModuleManifest.ExtensionPoints(List.of(), Arrays.asList((String) null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
