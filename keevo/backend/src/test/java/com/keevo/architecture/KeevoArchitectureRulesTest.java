package com.keevo.architecture;

import com.keevo.architecture.KeevoArchitectureRules.Rule;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Keevo ArchUnit rules — BLOCKING since Story 12.6b (spec-socle §13 rules 4, 6, 7 + transactional publishers).
 *
 * <p>Ratchet: a violation fails the build unless its key is listed, with a reason, in
 * {@code src/test/resources/architecture/<rule>-allowlist.txt} (format: {@link ArchitectureAllowlist}); an entry
 * that no longer matches a violation fails too (remove it) — both reported in one failure. Main classes only
 * ({@code DO_NOT_INCLUDE_TESTS}). {@code target/archunit-violations.txt} lists, per rule, its status (not run /
 * threw / ran), unexpected and allow-listed violations — written also when a rule fails.
 */
class KeevoArchitectureRulesTest {

    private static final Path VIOLATIONS_FILE = Path.of("target/archunit-violations.txt");

    private record Outcome(SortedMap<String, String> violations, Map<String, String> allowlist, Throwable error) {
    }

    private static JavaClasses mainClasses;
    private static final Map<Rule, Outcome> RESULTS = new EnumMap<>(Rule.class);

    @BeforeAll
    static void importClasses() throws IOException {
        Files.deleteIfExists(VIOLATIONS_FILE);
        mainClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.keevo");
    }

    @AfterAll
    static void writeReport() throws IOException {
        StringBuilder sb = new StringBuilder("=== Keevo ArchUnit rules (blocking since 12.6b) ===\n");
        for (Rule rule : Rule.values()) {
            sb.append('\n').append(rule.description).append('\n');
            Outcome outcome = RESULTS.get(rule);
            if (outcome == null) {
                sb.append("  status: NOT RUN\n");
                continue;
            }
            if (outcome.error() != null && outcome.violations() == null) {
                sb.append("  status: THREW ").append(outcome.error()).append('\n');
                continue;
            }
            Map<String, String> allow = outcome.allowlist() == null ? Map.of() : outcome.allowlist();
            List<String> unexpected = ArchitectureAllowlist.unexpected(outcome.violations(), allow);
            List<String> stale = ArchitectureAllowlist.stale(outcome.violations(), allow);
            sb.append("  status: ").append(outcome.error() != null ? "THREW " + outcome.error()
                : unexpected.isEmpty() && stale.isEmpty() ? "PASSED" : "FAILED").append('\n');
            sb.append("  unexpected (").append(unexpected.size()).append("):\n");
            unexpected.forEach(u -> sb.append("    - ").append(u).append('\n'));
            sb.append("  stale allow-list entries (").append(stale.size()).append("):\n");
            stale.forEach(k -> sb.append("    - ").append(k).append('\n'));
            sb.append("  allow-listed (").append(outcome.violations().size() - unexpected.size()).append("):\n");
            outcome.violations().forEach((key, detail) -> {
                if (allow.containsKey(key)) {
                    sb.append("    - ").append(key).append(": ").append(detail)
                        .append("  [reason: ").append(allow.get(key)).append("]\n");
                }
            });
        }
        Files.createDirectories(VIOLATIONS_FILE.getParent());
        Files.writeString(VIOLATIONS_FILE, sb.toString());
    }

    @Test
    void tenantSchemaJdbc_validatedThroughTenantSchema() {
        check(Rule.TENANT_SCHEMA_JDBC);
    }

    @Test
    void persistenceTypes_onlyInPersistenceAdapters() {
        check(Rule.PERSISTENCE_LOCATION);
    }

    @Test
    void domainAndApplicationLayers_haveNoWebTypes() {
        check(Rule.WEB_FREE_APPLICATION);
    }

    @Test
    void afterCommitListenedEvents_publishedInsideATransaction() {
        check(Rule.TRANSACTIONAL_PUBLISHERS);
    }

    @Test
    void capabilities_neverInjected() {
        check(Rule.CAPABILITY_INJECTION);
    }

    private static void check(Rule rule) {
        SortedMap<String, String> violations;
        try {
            violations = KeevoArchitectureRules.violations(rule, mainClasses);
        } catch (RuntimeException | Error e) {
            RESULTS.put(rule, new Outcome(null, null, e));
            throw e;
        }
        Map<String, String> allowlist;
        try {
            allowlist = loadAllowlist(rule);
        } catch (IOException | RuntimeException e) {
            RESULTS.put(rule, new Outcome(violations, null, e));
            fail("cannot read " + allowlistPath(rule) + ": " + e.getMessage(), e);
            return;
        }
        RESULTS.put(rule, new Outcome(violations, allowlist, null));

        List<String> unexpected = ArchitectureAllowlist.unexpected(violations, allowlist);
        List<String> stale = ArchitectureAllowlist.stale(violations, allowlist);
        if (!unexpected.isEmpty() || !stale.isEmpty()) {
            fail(rule.description
                + "\nUnexpected violations (fix the code, or — justified exception only — add '<key>   # <reason>' to "
                + allowlistPath(rule) + "):\n  " + String.join("\n  ", unexpected)
                + "\nStale allow-list entries (no longer violating — remove them):\n  " + String.join("\n  ", stale));
        }
    }

    private static String allowlistPath(Rule rule) {
        return "/architecture/" + rule.id + "-allowlist.txt";
    }

    private static Map<String, String> loadAllowlist(Rule rule) throws IOException {
        try (InputStream in = KeevoArchitectureRulesTest.class.getResourceAsStream(allowlistPath(rule))) {
            assertThat(in).as("allow-list " + allowlistPath(rule) + " (may be empty, must exist)").isNotNull();
            return ArchitectureAllowlist.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
