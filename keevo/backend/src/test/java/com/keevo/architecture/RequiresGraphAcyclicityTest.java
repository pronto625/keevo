package com.keevo.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule (d) of Story 12.6b / spec-socle §13 rule 5: the {@code requires} graph read from the module manifests
 * ({@code module.json}, spec-socle §3) is acyclic. No manifest exists before Epic 13, so the manifest test is
 * disabled; the cycle finder itself is checked now.
 */
class RequiresGraphAcyclicityTest {

    @Test
    @Disabled("enabled with Epic 13 manifests")
    void requiresGraphReadFromManifestsIsAcyclic() throws IOException {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:module.json")) {
            try (InputStream in = resource.getInputStream()) {
                JsonNode manifest = mapper.readTree(in);
                List<String> requires = new ArrayList<>();
                manifest.path("requires").forEach(r -> requires.add(r.asText()));
                graph.put(manifest.path("id").asText(), requires);
            }
        }

        assertThat(graph).as("module.json manifests on the classpath").isNotEmpty();
        assertThat(findCycle(graph)).as("cycle in the requires graph").isEmpty();
    }

    @Test
    void cycleFinderDetectsCyclesOnly() {
        assertThat(findCycle(Map.of(
            "pos", List.of("catalog", "inventory"), "inventory", List.of("catalog"),
            "catalog", List.of("core"), "transfers", List.of("inventory", "store")))).isEmpty();
        assertThat(findCycle(Map.of("a", List.of("b"), "b", List.of("c"), "c", List.of("a"))))
            .hasValueSatisfying(cycle -> assertThat(cycle).contains("a", "b", "c"));
        assertThat(findCycle(Map.of("a", List.of("a")))).isPresent();
    }

    /** First cycle found by DFS, as the path {@code [x, …, x]}; modules without a manifest are leaves. */
    static Optional<List<String>> findCycle(Map<String, List<String>> graph) {
        Map<String, Integer> state = new HashMap<>(); // 1 = on stack, 2 = done
        for (String start : graph.keySet()) {
            List<String> path = new ArrayList<>();
            Optional<List<String>> cycle = visit(start, graph, state, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> visit(String node, Map<String, List<String>> graph,
                                                Map<String, Integer> state, List<String> path) {
        Integer s = state.get(node);
        if (s != null && s == 2) {
            return Optional.empty();
        }
        if (s != null) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
            cycle.add(node);
            return Optional.of(cycle);
        }
        state.put(node, 1);
        path.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            Optional<List<String>> cycle = visit(next, graph, state, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        state.put(node, 2);
        return Optional.empty();
    }
}
