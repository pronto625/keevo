package com.keevo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.7a — grep gate (ADR-05, plans are data): no plan code literal drives behaviour in {@code src/main/java}.
 * A plan-dependent behaviour reads the plan's kind from the catalogue ({@code PlanCatalog} / a join on
 * {@code public.plans}), never compares a code.
 *
 * <p>Forbidden outside the allow-list: the seeded codes as string literals ({@code "FREE"}, {@code 'PREMIUM'}…), the
 * well-known constants {@code PlanType.FREE / PREMIUM_TRIAL / PREMIUM}, {@code PlanType.valueOf / values} and a trial
 * length written as an SQL interval. Comments are ignored.
 */
@DisplayName("Grep gate — no plan code literal in the production code (Story 19.7a)")
class PlanCodeLiteralGateTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern CODE_LITERAL = Pattern.compile("[\"'](FREE|PREMIUM|PREMIUM_TRIAL)[\"']");
    private static final Pattern CONSTANT = Pattern.compile("PlanType\\.(FREE|PREMIUM_TRIAL|PREMIUM)\\b");
    private static final Pattern CLOSED_LIST = Pattern.compile("PlanType\\.(valueOf|values)\\s*\\(");
    private static final Pattern TRIAL_INTERVAL = Pattern.compile("(?i)interval\\s*'\\d+\\s*months?'");

    /** File → why its plan-code literals or constants are legitimate. */
    private static final Map<String, String> ALLOWED = Map.of(
            "com/keevo/identity/api/PlanType.java", "the value type's well-known constants",
            "com/keevo/identity/api/PlanKind.java", "the V1 compatibility mapping (kind → V1 code)",
            "com/keevo/shared/capability/entitlement/PlanCatalog.java", "the catalogue's built-in fallback (V42 seed)",
            // not plan codes: tenant_modules.tier (V29 FREE|STANDARD|PREMIUM|ENTERPRISE)
            "com/keevo/shared/module/application/PlanModuleRows.java", "module tier constants",
            "com/keevo/shared/module/application/ModuleLifecycleService.java", "module tier constant",
            "com/keevo/shared/module/application/port/TenantModuleStatePort.java", "module tier of Row.of",
            "com/keevo/shared/module/adapter/out/persistence/JdbcTenantModuleStateStore.java",
            "module tier of a free module row",
            // seeds, not behaviour
            "com/keevo/identity/auth/adapter/in/bootstrap/AdminAccountInitializer.java",
            "the system tenant's seeded plan",
            "com/keevo/subscription/plan/adapter/in/bootstrap/PlanMigrationJob.java",
            "one-shot ADR-06 data migration: Keevo PREMIUM becomes TEAM (Story 19.7b)");

    @Test
    @DisplayName("no plan code literal, well-known constant, closed-list call or trial interval outside the allow-list")
    void noPlanCodeLiteral() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                boolean allowed = ALLOWED.containsKey(relative);
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String code = codeOf(lines.get(i));
                    boolean literal = CODE_LITERAL.matcher(code).find() || CONSTANT.matcher(code).find();
                    if ((literal && !allowed) || CLOSED_LIST.matcher(code).find()
                            || TRIAL_INTERVAL.matcher(code).find()) {
                        offences.add(relative + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(offences).as("plan codes are data: read the kind from PlanCatalog / join public.plans").isEmpty();
    }

    @Test
    @DisplayName("the allow-list stays exact: every allowed file still exists and still needs its exception")
    void allowListIsMinimal() throws IOException {
        for (String relative : ALLOWED.keySet()) {
            Path file = MAIN.resolve(relative);
            assertThat(file).as(relative).exists();
            boolean needed = Files.readAllLines(file).stream().map(PlanCodeLiteralGateTest::codeOf)
                    .anyMatch(l -> CODE_LITERAL.matcher(l).find() || CONSTANT.matcher(l).find());
            assertThat(needed).as(relative + " no longer needs its exception: remove it").isTrue();
        }
        assertThat(Set.copyOf(ALLOWED.values())).isNotEmpty();
    }

    /** The code part of a line: comment lines dropped, a trailing {@code //} comment cut when outside a string. */
    private static String codeOf(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return "";
        }
        for (int at = line.indexOf("//"); at >= 0; at = line.indexOf("//", at + 2)) {
            long quotes = line.substring(0, at).chars().filter(c -> c == '"').count();
            if (quotes % 2 == 0) {
                return line.substring(0, at);
            }
        }
        return line;
    }
}
