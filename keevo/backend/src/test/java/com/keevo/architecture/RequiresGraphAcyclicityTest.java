package com.keevo.architecture;

import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule (d) of Story 12.6b / spec-socle §13 rule 5, enabled by Story 13.1: the {@code requires} graph of the
 * module manifests ({@code keevo/modules/<id>/module.json}) is acyclic.
 *
 * <p>The manifests are read with the production loader ({@link ModuleManifestLoader}, the one the boot seed
 * uses), so this test and the boot agree on the glob and on the validation (non-blank unique ids, {@code requires}
 * a JSON array of known ids, no cycle) — no second parser. On top of that it checks that no manifest is silently
 * ignored: the 17 shipped modules (10 base + 7 paid feature modules, Story 13.10a) are all loaded, no {@code module.json} sits outside
 * {@code src/main/resources/keevo/modules/<id>/}, and none is shipped from the test resources.
 */
class RequiresGraphAcyclicityTest {

    @Test
    void requiresGraphReadFromManifestsIsAcyclic() throws IOException {
        // throws on any invalid manifest
        List<LoadedModuleManifest> loaded = ModuleManifestLoader.loadFromClasspath(new PathMatchingResourcePatternResolver());

        assertThat(loaded).as("module.json manifests on the classpath").hasSize(18);
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (LoadedModuleManifest m : loaded) {
            ModuleManifest manifest = m.manifest();
            assertThat(manifest.id()).as("id of %s", m.location()).isNotBlank();
            assertThat(graph.put(manifest.id(), manifest.requires())).as("duplicate id %s", manifest.id()).isNull();
        }
        assertThat(graph).containsKey(ModuleManifest.CORE_ID);
        assertThat(ModuleManifestLoader.findRequiresCycle(graph)).as("cycle in the requires graph").isEmpty();
    }

    @Test
    void noManifestOutsideTheModulesDirectory() throws IOException, URISyntaxException {
        // module base dir from this class's location (target/test-classes → ../..), not from the CWD
        Path baseDir = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
            .getParent().getParent();
        assertThat(manifestsUnder(baseDir.resolve("src/main/resources")))
            .hasSize(18)
            .allSatisfy(p -> assertThat(p).matches("keevo/modules/[a-z][a-z0-9]*(-[a-z0-9]+)*/module\\.json"));
        assertThat(manifestsUnder(baseDir.resolve("src/test/resources"))).as("module.json in test resources").isEmpty();
    }

    private static List<String> manifestsUnder(Path root) throws IOException {
        assertThat(root).isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.getFileName().toString().equals("module.json"))
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .toList();
        }
    }

    @Test
    void cycleFinderDetectsCyclesOnly() {
        assertThat(ModuleManifestLoader.findRequiresCycle(Map.of(
            "pos", List.of("catalog", "inventory"), "inventory", List.of("catalog"),
            "catalog", List.of("core"), "transfers", List.of("inventory", "store")))).isEmpty();
        assertThat(ModuleManifestLoader.findRequiresCycle(Map.of("a", List.of("b"), "b", List.of("c"), "c", List.of("a"))))
            .hasValueSatisfying(cycle -> assertThat(cycle).contains("a", "b", "c"));
        assertThat(ModuleManifestLoader.findRequiresCycle(Map.of("a", List.of("a")))).isPresent();
    }
}
