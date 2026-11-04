package com.keevo.shared.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.11 — guard: the access token proves identity only. No server code reads a right
 * (role, store, account status, password-change flag) or the first name from token claims; the
 * legacy claims written for installed V1 apps are indicative only. A future service that changes a
 * right without calling the revocation still takes effect, because the filter never trusts the token.
 */
@DisplayName("16.11 — guard: no authorization from token claims")
class TokenClaimsGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Set<String> RIGHT_CLAIMS =
            Set.of("role", "tenantStatus", "storeId", "passwordChangeRequired", "firstName", "onboardingCompleted");
    /**
     * Files allowed to touch jjwt types: the provider (signing/parsing), the filter, the login-token
     * check, and two controllers that re-read identity claims only ({@code sub}, {@code tenantId}).
     */
    private static final Set<String> JJWT_USERS = Set.of(
            "com/keevo/shared/infrastructure/security/JwtTokenProvider.java",
            "com/keevo/shared/infrastructure/security/JwtAuthFilter.java",
            "com/keevo/identity/auth/application/service/LoginTokens.java", // 19.2 — login-token check shared by select-tenant and create-company
            "com/keevo/sync/sync/adapter/in/rest/SyncController.java",
            "com/keevo/commerce/sale/adapter/in/rest/DayClosureController.java");
    private static final Pattern CLAIM_READ = Pattern.compile("\\.get\\(\\s*\"(\\w+)\"");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String rel(Path p) {
        return MAIN.relativize(p).toString().replace('\\', '/');
    }

    @Test
    @DisplayName("JwtTokenProvider exposes no extractor of a right or of the first name")
    void providerHasNoRightExtractor() {
        Set<String> forbidden = Set.of("extractRole", "extractTenantStatus", "extractStoreId",
                "extractPasswordChangeRequired", "extractFirstName");
        List<String> found = Arrays.stream(JwtTokenProvider.class.getDeclaredMethods())
                .map(Method::getName).filter(forbidden::contains).toList();
        assertThat(found).isEmpty();
        // The only legacy read (STORE_REASSIGNED compatibility) is package-private, for the filter.
        assertThat(Arrays.stream(JwtTokenProvider.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("extractLegacyStoreId"))
                .allMatch(m -> !Modifier.isPublic(m.getModifiers()))).isTrue();
    }

    @Test
    @DisplayName("only the provider, the filter and the login-token check touch jjwt; none reads a right claim")
    void noClaimBasedAuthorization() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String name = rel(file);
            String src = Files.readString(file);
            boolean usesJjwt = src.contains("io.jsonwebtoken.");
            if (usesJjwt && !JJWT_USERS.contains(name)) {
                violations.add(name + " uses jjwt claims");
            }
            if (!usesJjwt) continue;
            var m = CLAIM_READ.matcher(src);
            while (m.find()) {
                String claim = m.group(1);
                boolean legacyStoreCompat = name.endsWith("JwtTokenProvider.java") && claim.equals("storeId")
                        && src.substring(Math.max(0, m.start() - 400), m.start()).contains("extractLegacyStoreId");
                if (RIGHT_CLAIMS.contains(claim) && !legacyStoreCompat) {
                    violations.add(name + " reads claim \"" + claim + "\"");
                }
            }
            if (src.contains("extractLegacyStoreId(") && !name.endsWith("JwtTokenProvider.java")
                    && !name.endsWith("JwtAuthFilter.java")) {
                violations.add(name + " reads the legacy storeId claim");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("no token ever carries the first name")
    void noFirstNameClaimWritten() throws IOException {
        for (Path file : sources()) {
            assertThat(Files.readString(file)).as(rel(file)).doesNotContain("claim(\"firstName\"");
        }
    }
}
