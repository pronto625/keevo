package com.keevo.shared.module;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 13.1 — the 10 manifests shipped in {@code src/main/resources/keevo/modules} load and carry the decided graph. */
class ClasspathModuleManifestsTest {

    @Test
    void theTenShippedManifestsAreValid() {
        Map<String, ModuleManifest> byId = ModuleManifestLoader.loadFromClasspath(new PathMatchingResourcePatternResolver()).stream()
                .map(LoadedModuleManifest::manifest)
                .collect(Collectors.toMap(ModuleManifest::id, Function.identity()));

        assertThat(byId).containsOnlyKeys("core", "catalog", "crm", "inventory", "store", "pos", "reporting",
                "messaging", "sync", "admin");
        assertThat(byId.values()).allSatisfy(m -> {
            assertThat(m.version()).isEqualTo(m.id().equals("crm") ? "1.1.0" : "1.0.0"); // 13.6: crm provides
            assertThat(m.free()).isTrue();
            assertThat(m.entitlement()).isEqualTo("module." + m.id());
        });
        assertThat(byId.get("core").category()).isEqualTo(ModuleCategory.CORE);
        assertThat(byId.get("core").requires()).isEmpty();
        assertThat(byId.get("sync").category()).isEqualTo(ModuleCategory.CORE);
        assertThat(byId.get("admin").category()).isEqualTo(ModuleCategory.PLATFORM);
        assertThat(byId.get("pos").requires()).containsExactly("core", "catalog", "inventory");
        assertThat(byId.get("pos").enhances()).containsExactly("crm", "reporting");
        assertThat(byId.get("inventory").requires()).containsExactly("core", "catalog");
        assertThat(byId.get("reporting").enhances()).containsExactly("pos", "inventory", "catalog");
        assertThat(byId.get("messaging").enhances()).containsExactly("reporting", "pos", "inventory");
        assertThat(byId.get("catalog").enhances()).containsExactly("reporting");
        assertThat(byId.get("crm").enhances()).containsExactly("pos");
        assertThat(byId.get("crm").provides()).containsExactly("ClientLookupPort");
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("crm"))
                .allSatisfy(m -> assertThat(m.provides()).isEmpty());
        assertThat(byId.values()).filteredOn(m -> !m.isCore())
                .allSatisfy(m -> assertThat(m.requires()).contains("core"));
        assertThat(byId.values().stream().filter(m -> m.category() == ModuleCategory.BUSINESS).map(ModuleManifest::id)
                .toList()).hasSize(7);
        assertThat(List.copyOf(byId.values())).allSatisfy(m -> assertThat(m.displayName()).isNotBlank());
    }
}
