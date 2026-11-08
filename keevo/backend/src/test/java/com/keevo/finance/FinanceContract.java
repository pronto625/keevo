package com.keevo.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.11 — the shared contract {@code keevo/contracts/finance-math-cases.json} and its matcher, used by the unit
 * contract test and by the end-to-end run of its summary cases through {@code GET /api/v1/finance/summary}.
 */
public final class FinanceContract {

    private FinanceContract() {
    }

    public static JsonNode load(ObjectMapper json) throws IOException {
        return json.readTree(Path.of("..", "contracts", "finance-math-cases.json").toFile());
    }

    /** Compares a serialized summary with a contract summary case's {@code expected}. */
    public static void assertSummary(JsonNode expected, JsonNode actual, String name) {
        for (String section : List.of("period", "revenue", "expenses", "result", "comparison")) {
            assertSubset(expected.path(section), actual.path(section), name + " / " + section);
        }
        assertThat(actual.path("byCategory")).as(name).hasSize(expected.path("byCategory").size());
        for (int i = 0; i < expected.path("byCategory").size(); i++) {
            assertSubset(expected.path("byCategory").get(i), actual.path("byCategory").get(i), name + " / cat " + i);
        }
        assertThat(actual.path("series")).as(name).hasSize(expected.path("seriesSize").asInt());
        Map<String, JsonNode> points = new HashMap<>();
        actual.path("series").forEach(p -> points.put(p.path("key").asText(), p));
        for (JsonNode point : expected.path("series")) {
            assertSubset(point, points.get(point.path("key").asText()), name + " / " + point.path("key"));
        }
    }

    /**
     * Every field of {@code expected} is in {@code actual} with the same value: numbers by value, nested objects
     * recursively; an expected {@code null} requires the field to be present and null.
     */
    public static void assertSubset(JsonNode expected, JsonNode actual, String where) {
        assertThat(actual).as(where + ": missing").isNotNull();
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            String at = where + "." + f.getKey();
            assertThat(actual.has(f.getKey())).as(at + ": field missing").isTrue();
            JsonNode a = actual.get(f.getKey());
            if (f.getValue().isNull()) {
                assertThat(a.isNull()).as(at + " = " + a).isTrue();
            } else if (f.getValue().isNumber()) {
                assertThat(a.isNumber()).as(at + " = " + a).isTrue();
                assertThat(a.decimalValue()).as(at).isEqualByComparingTo(f.getValue().decimalValue());
            } else if (f.getValue().isObject()) {
                assertSubset(f.getValue(), a, at);
            } else {
                assertThat(a.asText()).as(at).isEqualTo(f.getValue().asText());
            }
        }
    }
}
