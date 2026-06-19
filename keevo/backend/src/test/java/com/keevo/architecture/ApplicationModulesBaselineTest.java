package com.keevo.architecture;

import com.keevo.KeevoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ApplicationModulesBaselineTest {

    private static final Path VIOLATIONS_FILE =
        Path.of("target/modulith-violations.txt");

    @Test
    void detectModulesAndGenerateDocumentation() throws IOException {
        ApplicationModules modules = ApplicationModules.of(KeevoApplication.class);

        // AC1: Generate C4 + PlantUML docs → target/modulith-docs/
        new Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
            .writeDocumentation();
    }

    @Test
    void verifyModuleBoundaries_warningModeOnly() throws IOException {
        ApplicationModules modules = ApplicationModules.of(KeevoApplication.class);

        // AC2, AC3, AC4: verify() in warning mode — captures violations without failing
        try {
            modules.verify();
            Files.createDirectories(VIOLATIONS_FILE.getParent());
            Files.writeString(VIOLATIONS_FILE, "✓ No module violations detected.\n");
        } catch (Exception | AssertionError e) {
            // Baseline mode: document violations, do NOT fail the build
            // Spring Modulith 1.4 throws Violations (RuntimeException), not AssertionError
            String report = """
                === Spring Modulith Violation Report (baseline — warning mode) ===
                Total violations: counted in details below
                Run: ApplicationModules.of(KeevoApplication.class).verify()
                Status: NON-BLOCKING (passes in Epic 10, becomes blocking in Epic 12.2e)
                
                === Violation Details ===
                %s
                """.formatted(e.getMessage());
            Files.createDirectories(VIOLATIONS_FILE.getParent());
            Files.writeString(VIOLATIONS_FILE, report);
            // DO NOT rethrow — warning mode
        }
    }
}
