package com.keevo.shared.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 16.12 — the foundation scripts are read strictly: misnamed files and conflicting duplicates fail. */
@DisplayName("FoundationScripts (Story 16.12)")
class FoundationScriptsTest {

    private static ModuleManifest free(String id) {
        return TestModuleCatalogs.loaded(id, ModuleCategory.BUSINESS, true).manifest();
    }

    private static Resource script(String name, String sql) {
        return new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }

    /** A resolver returning {@code resources} for the module folder of {@code moduleId}, nothing elsewhere. */
    private static ResourcePatternResolver resolver(String moduleId, Resource... resources) {
        return new PathMatchingResourcePatternResolver() {
            @Override
            public Resource[] getResources(String pattern) {
                return pattern.contains("/" + moduleId + "/") ? resources : new Resource[0];
            }
        };
    }

    @Test
    @DisplayName("shipped build: the 9 foundation modules, version order, each script once")
    void shipped() {
        FoundationScripts scripts = FoundationScripts.fromClasspath();
        assertThat(scripts.moduleIds()).hasSize(9);
        assertThat(scripts.scripts()).extracting(FoundationScripts.Script::name).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a misnamed .sql in a foundation module fails instead of being skipped")
    void misnamed() {
        assertThatThrownBy(() -> FoundationScripts.load(List.of(free("provmisnamed")),
                new PathMatchingResourcePatternResolver()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Misnamed");
    }

    @Test
    @DisplayName("the same version on two classpath roots: identical text kept once, different text fails")
    void duplicates() {
        FoundationScripts same = FoundationScripts.load(List.of(free("dup")), resolver("dup",
                script("V1__dup.sql", "SELECT 1;"), script("V1__dup.sql", "SELECT 1;"),
                script("V2__more.sql", "SELECT 2;")));
        assertThat(same.scripts()).extracting(FoundationScripts.Script::version).containsExactly("1", "2");

        assertThatThrownBy(() -> FoundationScripts.load(List.of(free("dup")), resolver("dup",
                script("V1__dup.sql", "SELECT 1;"), script("V1__dup.sql", "SELECT 99;"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("twice");
    }
}
