package com.keevo.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit baseline rules — WARNING MODE (non-blocking).
 *
 * Rules document current violations without failing the build.
 * Will be made blocking in Story 12.6.
 */
class KeevoArchUnitBaselineTest {

    private static final Path VIOLATIONS_FILE =
        Path.of("target/archunit-violations.txt");

    private static JavaClasses keevoClasses;

    @BeforeAll
    static void importClasses() {
        keevoClasses = new ClassFileImporter().importPackages("com.keevo");
    }

    @Test
    void architectureViolations_warningModeOnly() throws IOException {
        List<String> violations = new ArrayList<>();

        // Rule 1: No JPA entities outside adapter/out/persistence
        checkRule(
            noClasses()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideOutsideOfPackage("..adapter.out.persistence.."),
            "Rule 1: @Entity classes outside adapter/out/persistence",
            violations
        );

        // Rule 2: No Spring web types in domain layer
        checkRule(
            noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web.."),
            "Rule 2: Spring web dependencies in domain layer",
            violations
        );

        // Rule 3: No Spring web types in application/service layer
        checkRule(
            noClasses()
                .that().resideInAPackage("..application.service..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web.."),
            "Rule 3: Spring web dependencies in application/service layer",
            violations
        );

        // Write report
        Files.createDirectories(VIOLATIONS_FILE.getParent());
        if (violations.isEmpty()) {
            Files.writeString(VIOLATIONS_FILE, "✓ No ArchUnit violations detected.\n");
        } else {
            Files.writeString(VIOLATIONS_FILE,
                "=== ArchUnit Violation Report (baseline — warning mode) ===\n" +
                "Total rules with violations: " + violations.size() + "\n\n" +
                String.join("\n---\n", violations));
        }
        // DO NOT fail — warning mode until Story 12.6
    }

    private void checkRule(ArchRule rule, String ruleName, List<String> violations) {
        try {
            rule.check(keevoClasses);
        } catch (AssertionError e) {
            violations.add(ruleName + ":\n" + e.getMessage());
        }
    }
}
