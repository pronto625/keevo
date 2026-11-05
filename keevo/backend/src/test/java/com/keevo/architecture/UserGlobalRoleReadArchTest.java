package com.keevo.architecture;

import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.identity.auth.domain.model.User;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.6 — BLOCKING rule: once a person may hold different roles in different companies, the global
 * {@code users.role} is informational. Every authorization decision reads the membership of the current company; the
 * only allowed read of {@link User#getRole()} in main code is the platform-administrator check ({@code SUPER_ADMIN} on
 * the same source line) and the persistence mapping ({@link UserRepositoryAdapter}).
 *
 * <p>Bytecode finds every access (call or method reference, lambdas included) with its line; the line of source is
 * then read to check that it names {@code SUPER_ADMIN}.
 */
class UserGlobalRoleReadArchTest {

    /** {@code src/main/java} of this module: system property {@code keevo.mainSources}, else from target/test-classes. */
    private static final Path MAIN_SOURCES = mainSources();

    /** SQL reading the global role: {@code u.role}, {@code <alias>_u.role}, {@code users.role}. */
    private static final Pattern SQL_GLOBAL_ROLE = Pattern.compile("(\\b\\w*_u|\\bu|\\busers)\\.role\\b");

    @Test
    @DisplayName("User.getRole() is only read for SUPER_ADMIN checks (and by the persistence mapping)")
    void globalRoleOnlyReadForSuperAdminChecks() throws IOException {
        JavaClasses mainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.keevo");

        Map<Path, List<String>> sources = new HashMap<>();
        List<String> violations = new ArrayList<>();
        int superAdminChecks = 0;
        for (JavaClass javaClass : mainClasses) {
            for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                if (!"getRole".equals(access.getTarget().getName())
                        || !access.getTarget().getOwner().isEquivalentTo(User.class)) {
                    continue;
                }
                JavaClass origin = access.getOriginOwner();
                if (origin.isEquivalentTo(UserRepositoryAdapter.class)
                        || origin.isEquivalentTo(User.class)) {
                    continue; // persistence mapping, the class itself
                }
                int lineNumber = access.getLineNumber();
                if (lineNumber <= 0) {
                    violations.add(origin.getName() + ": access without line number, cannot be checked");
                    continue;
                }
                Path file = sourceFile(origin);
                List<String> lines = sources.computeIfAbsent(file, UserGlobalRoleReadArchTest::read);
                String line = lines.get(lineNumber - 1);
                if (line.contains("SUPER_ADMIN")) {
                    superAdminChecks++;
                } else {
                    violations.add(origin.getName() + ":" + access.getLineNumber() + " → " + line.strip());
                }
            }
        }

        assertThat(violations)
                .as("User.getRole() read outside a SUPER_ADMIN check: read the membership of the current company")
                .isEmpty();
        assertThat(superAdminChecks).as("the rule found the known SUPER_ADMIN checks").isGreaterThan(0);
    }

    @Test
    @DisplayName("SQL never reads users.role (u.role, <alias>_u.role) outside SUPER_ADMIN detection and the mapping")
    void sqlNeverReadsGlobalRole() throws IOException {
        Set<Path> exempt = Set.of(
                MAIN_SOURCES.resolve("com/keevo/identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java"),
                MAIN_SOURCES.resolve("com/keevo/identity/auth/domain/model/User.java"));
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (exempt.contains(file)) continue;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) continue;
                    if (SQL_GLOBAL_ROLE.matcher(line).find() && !line.contains("SUPER_ADMIN")) {
                        violations.add(MAIN_SOURCES.relativize(file) + ":" + (i + 1) + " → " + line);
                    }
                }
            }
        }
        assertThat(violations)
                .as("SQL reading users.role: join public.user_tenant_memberships of the current company instead")
                .isEmpty();
    }

    @Test
    @DisplayName("the SQL pattern catches the known shapes")
    void sqlPatternSelfCheck() {
        assertThat(SQL_GLOBAL_ROLE.matcher("CASE WHEN lb_u.role = 'OWNER'").find()).isTrue();
        assertThat(SQL_GLOBAL_ROLE.matcher("SELECT u.role FROM public.users u").find()).isTrue();
        assertThat(SQL_GLOBAL_ROLE.matcher("WHERE public.users.role = 'OWNER'").find()).isTrue();
        assertThat(SQL_GLOBAL_ROLE.matcher("lb_m.role = 'OWNER'").find()).isFalse();
        assertThat(SQL_GLOBAL_ROLE.matcher("m_owner.role = 'OWNER'").find()).isFalse();
    }

    private static Path mainSources() {
        String configured = System.getProperty("keevo.mainSources");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        try {
            Path testClasses = Path.of(UserGlobalRoleReadArchTest.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()); // <module>/target/test-classes
            return testClasses.getParent().getParent().resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path sourceFile(JavaClass origin) {
        String fileName = origin.getSourceCodeLocation().getSourceFileName();
        return MAIN_SOURCES.resolve(origin.getPackageName().replace('.', '/')).resolve(fileName);
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new IllegalStateException("source not found: " + file, e);
        }
    }
}
