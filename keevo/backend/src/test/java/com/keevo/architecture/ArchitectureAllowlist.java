package com.keevo.architecture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ratchet allow-list of {@link KeevoArchitectureRulesTest} (Story 12.6b): one {@code <key>   # <reason>} per line,
 * reason mandatory, key without whitespace ({@code Class#method(Params)} for rule e). Blank lines and lines
 * starting with {@code #} are ignored; a duplicate key or a missing reason is an error.
 */
final class ArchitectureAllowlist {

    private static final Pattern ENTRY = Pattern.compile("^(\\S+)\\s+#(.*)$");
    private static final char BOM = '﻿';

    private ArchitectureAllowlist() {
    }

    /** @throws IllegalArgumentException on a duplicate key or an entry without a reason */
    static SortedMap<String, String> parse(Reader source) throws IOException {
        SortedMap<String, String> entries = new TreeMap<>();
        BufferedReader reader = new BufferedReader(source);
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            if (number == 1 && !line.isEmpty() && line.charAt(0) == BOM) {
                line = line.substring(1);
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher entry = ENTRY.matcher(trimmed);
            if (!entry.matches() || entry.group(2).isBlank()) {
                throw new IllegalArgumentException("line " + number + ": entry without a reason ('<key>   # <reason>'): "
                    + trimmed);
            }
            if (entries.putIfAbsent(entry.group(1), entry.group(2).strip()) != null) {
                throw new IllegalArgumentException("line " + number + ": duplicate key " + entry.group(1));
            }
        }
        return entries;
    }

    /** Violations whose key is not allow-listed, as {@code key — detail}. */
    static List<String> unexpected(Map<String, String> violations, Map<String, String> allowlist) {
        List<String> out = new ArrayList<>();
        violations.forEach((key, detail) -> {
            if (!allowlist.containsKey(key)) {
                out.add(key + " — " + detail);
            }
        });
        return out;
    }

    /** Allow-list keys that no longer match a violation. */
    static List<String> stale(Map<String, String> violations, Map<String, String> allowlist) {
        return allowlist.keySet().stream().filter(k -> !violations.containsKey(k)).toList();
    }
}
