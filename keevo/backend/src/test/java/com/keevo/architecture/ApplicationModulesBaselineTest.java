package com.keevo.architecture;

import com.keevo.KeevoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;
import org.springframework.modulith.docs.Documenter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationModulesBaselineTest {

    private static final Path VIOLATIONS_FILE =
        Path.of("target/modulith-violations.txt");

    /** The 13 top-level packages under com.keevo (story 12.1; crm split out of catalog in 12.5). */
    private static final Set<String> EXPECTED_MODULES = Set.of(
        "admin", "catalog", "commerce", "crm", "feedback", "identity", "inventory",
        "messaging", "reporting", "shared", "store", "subscription", "sync");

    /**
     * "depends on module X via ... Allowed targets: ..." — Modulith uses this format both when X is not
     * declared (real not-allowed) and when X is declared but the referenced type is internal to X.
     * Once 12.2x publishes APIs the target reads "named interface(s) 'X :: api'" and allowed targets may
     * carry a " :: <interface>" suffix; both are reduced to the module name (see {@link #moduleOf}).
     */
    static final Pattern MODULE_DEPENDENCY =
        Pattern.compile("^Module '([^']+)' depends on (?:module|named interface\\(s\\)) '([^']+)' via .* Allowed targets: (.*?)\\.?$", Pattern.DOTALL);
    /** Modules whose public API is published (12.2a catalog/crm, 12.2b inventory, 12.2c commerce, 12.2d identity/store/subscription): internals are off-limits. */
    static final Set<String> PUBLISHED_API_MODULES =
        Set.of("catalog", "crm", "inventory", "commerce", "identity", "store", "subscription");

    static final Pattern NON_EXPOSED_TYPE =
        Pattern.compile("^Module '([^']+)' depends on non-exposed type .* within module '([^']+)'.*", Pattern.DOTALL);

    @Test
    void detectsTheThirteenDeclaredModules() {
        ApplicationModules modules = ApplicationModules.of(KeevoApplication.class);

        Set<String> names = modules.stream()
            .map(ApplicationModule::getName)
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
    }

    @Test
    void detectModulesAndGenerateDocumentation() throws IOException {
        ApplicationModules modules = ApplicationModules.of(KeevoApplication.class);

        // Generate C4 + PlantUML docs → target/spring-modulith-docs/
        new Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
            .writeDocumentation();
    }

    @Test
    void verifyModuleBoundaries_warningModeOnly() throws IOException {
        ApplicationModules modules = ApplicationModules.of(KeevoApplication.class);

        // Warning mode — captures violations without failing (blocking from 12.2e)
        Violations violations = modules.detectViolations();
        Files.createDirectories(VIOLATIONS_FILE.getParent());

        if (!violations.hasViolations()) {
            Files.writeString(VIOLATIONS_FILE, "✓ No module violations detected.\n");
            return;
        }

        List<String> messages = violations.getMessages();
        Files.writeString(VIOLATIONS_FILE, buildReport(messages));
        // Cycles and non-exposed stay warning-only (blocking from 12.2e); the allow-lists are pinned:
        // a new undeclared module edge must be declared (or removed), never pass silently.
        assertThat(messages.stream().filter(m -> isModuleDependency(m, false)).toList())
            .as("not-allowed module dependencies (undeclared target) — see " + VIOLATIONS_FILE)
            .isEmpty();
        // 12.2a–12.2d pinned: no module but shared reaches into a published module's internals
        // (a missing type-level @NamedInterface("events") on a new event lands here too).
        assertThat(messages.stream().filter(ApplicationModulesBaselineTest::isInternalAccessToPublishedModule).toList())
            .as("non-exposed access to published-module internals (use their 'api'/'events') — see " + VIOLATIONS_FILE)
            .isEmpty();
    }

    /**
     * True for a non-exposed-type or internal-of-declared-module violation whose source is not
     * {@code shared} and whose target is a module with a published API ({@link #PUBLISHED_API_MODULES}).
     */
    static boolean isInternalAccessToPublishedModule(String message) {
        Matcher matcher = NON_EXPOSED_TYPE.matcher(message);
        if (!matcher.matches()) {
            if (!isModuleDependency(message, true)) {
                return false;
            }
            matcher = MODULE_DEPENDENCY.matcher(message);
            matcher.matches();
        }
        return !"shared".equals(matcher.group(1)) && PUBLISHED_API_MODULES.contains(moduleOf(matcher.group(2)));
    }

    private static String buildReport(List<String> messages) {
        List<String> cycles = messages.stream().filter(m -> m.startsWith("Cycle detected")).toList();
        List<String> notAllowed = messages.stream().filter(m -> isModuleDependency(m, false)).toList();
        List<String> internalOfAllowed = messages.stream().filter(m -> isModuleDependency(m, true)).toList();
        List<String> nonExposedType = messages.stream().filter(m -> NON_EXPOSED_TYPE.matcher(m).matches()).toList();
        List<String> nonExposed = new ArrayList<>(nonExposedType);
        nonExposed.addAll(internalOfAllowed);
        long other = messages.size() - cycles.size() - nonExposed.size() - notAllowed.size();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Spring Modulith Violation Report (baseline — warning mode) ===\n");
        sb.append("Total violations: ").append(messages.size()).append('\n');
        sb.append("  cycle: ").append(cycles.size()).append('\n');
        sb.append("  non-exposed: ").append(nonExposed.size())
            .append(" (").append(nonExposedType.size()).append(" non-exposed type references, ")
            .append(internalOfAllowed.size()).append(" internal types of a declared module)\n");
        sb.append("  not-allowed (target module not declared): ").append(notAllowed.size()).append('\n');
        sb.append("  other: ").append(other).append('\n');
        sb.append("Run: ApplicationModules.of(KeevoApplication.class).detectViolations()\n");
        sb.append("Status: NON-BLOCKING (becomes blocking in Epic 12.2e)\n\n");

        appendCounts(sb, "Not-allowed violations by source module", notAllowed, MODULE_DEPENDENCY, m -> m.group(1));
        appendCounts(sb, "Not-allowed violations by edge (source -> target)", notAllowed, MODULE_DEPENDENCY,
            m -> m.group(1) + " -> " + moduleOf(m.group(2)));
        appendCounts(sb, "Non-exposed violations by source module", nonExposed, null, m -> m.group(1));
        appendCounts(sb, "Non-exposed violations by edge (source -> target) — shared -> X are shared depending on other modules' internals",
            nonExposed, null, m -> m.group(1) + " -> " + moduleOf(m.group(2)));

        sb.append("=== Violation Details ===\n");
        messages.forEach(m -> sb.append("- ").append(m).append('\n'));
        return sb.toString();
    }

    static boolean isModuleDependency(String message, boolean targetDeclared) {
        Matcher matcher = MODULE_DEPENDENCY.matcher(message);
        if (!matcher.matches()) {
            return false;
        }
        Set<String> allowed = new TreeSet<>();
        for (String target : matcher.group(3).trim().split("\\s*,\\s*")) {
            allowed.add(moduleOf(target));
        }
        return allowed.contains(moduleOf(matcher.group(2))) == targetDeclared;
    }

    /** "catalog :: api" (or "catalog :: api, catalog :: spi") -> "catalog"; a plain module name is returned as is. */
    static String moduleOf(String target) {
        return target.split(",")[0].split("::")[0].trim();
    }

    /** {@code pattern == null}: try both module-dependency formats (groups 1 = source, 2 = target). */
    private static void appendCounts(StringBuilder sb, String title, List<String> messages,
                                     Pattern pattern, Function<Matcher, String> key) {
        Map<String, Long> counts = new TreeMap<>();
        for (String message : messages) {
            Matcher matcher = pattern != null ? pattern.matcher(message) : MODULE_DEPENDENCY.matcher(message);
            if (pattern == null && !matcher.matches()) {
                matcher = NON_EXPOSED_TYPE.matcher(message);
            }
            if (matcher.matches()) {
                counts.merge(key.apply(matcher), 1L, Long::sum);
            }
        }
        sb.append("=== ").append(title).append(" ===\n");
        if (counts.isEmpty()) {
            sb.append("  (none)\n");
        }
        counts.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        sb.append('\n');
    }
}
