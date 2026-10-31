package com.keevo.shared.module;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 15.2 — the shared route contract file {@code keevo/contracts/module-routes.json}: module id → the app route
 * patterns of that module, generated from the shipped manifests (modules without routes omitted, ids sorted, routes
 * in manifest order).
 *
 * <p>The app route guard sends a route of a module that is not readable to the « module non activé » screen; its
 * registry ({@code keevo/app/lib/core/router/module_routes.dart}) is checked against this file by the app test
 * {@code test/core/router/module_routes_contract_test.dart}. A manifest change breaks this test until the file is
 * regenerated, then the app test until the registry follows:
 * <pre>
 * cd keevo/backend
 * mvn -o test -Dtest=ModuleRoutesContractFileTest -Dmodule.routes.contract.write=true
 * </pre>
 */
@DisplayName("Module routes contract file — keevo/contracts/module-routes.json (15.2)")
class ModuleRoutesContractFileTest {

    private static final Path CONTRACT = Path.of("..", "contracts", "module-routes.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the contract file equals the routes declared by the shipped manifests")
    void contractFileMatchesManifests() throws Exception {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        ModuleManifestLoader.loadFromClasspath(new PathMatchingResourcePatternResolver()).stream()
                .map(LoadedModuleManifest::manifest)
                .filter(m -> !m.routes().isEmpty())
                .forEach(m -> expected.put(m.id(), m.routes())); // loader output is sorted by id
        if (Boolean.getBoolean("module.routes.contract.write")) {
            write(expected);
        }
        assertThat(Files.exists(CONTRACT))
                .as("missing %s — run with -Dmodule.routes.contract.write=true", CONTRACT.toAbsolutePath())
                .isTrue();
        JsonNode actual = mapper.readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));
        assertThat(actual)
                .as("%s is stale — regenerate with -Dmodule.routes.contract.write=true", CONTRACT)
                .isEqualTo(mapper.valueToTree(expected));
    }

    private void write(Map<String, List<String>> contract) throws Exception {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        String json = mapper.writer(printer).writeValueAsString(contract) + "\n";
        Files.createDirectories(CONTRACT.toAbsolutePath().getParent());
        Files.writeString(CONTRACT, json, StandardCharsets.UTF_8);
    }
}
