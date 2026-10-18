package com.keevo.shared.infrastructure.config;

import com.keevo.KeevoApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * ProdSecretsFailFastContextTest — proves the fail-fast at Spring-context level (story v1s-12-13).
 *
 * <p>Boots the real {@link KeevoApplication} under the {@code prod} profile against an unreachable
 * datasource. {@code @SpringBootTest} cannot assert a context that fails to start, hence
 * {@link SpringApplicationBuilder}. Every property is passed as a command-line argument (highest
 * precedence) so a local {@code .env} (imported via {@code spring.config.import}) cannot leak in.
 *
 * <p>A {@code @PostConstruct} failure surfaces as {@code BeanCreationException} whose root is the
 * validator's {@link IllegalStateException}: the assertions walk the cause chain.
 */
@DisplayName("ProdSecretsValidator — Spring-context fail-fast (prod profile)")
@ExtendWith(OutputCaptureExtension.class)
class ProdSecretsFailFastContextTest {

    private static final String VALID_DB_PASSWORD = "3f9a1c7e5b2d4f6a8c0e1b3d5f7a9c2e";
    private static final String VALID_ADMIN_PASSWORD = "Str0ngPr0dP@ss!";

    @TempDir
    static Path keyDir;

    private static Path privateKey;
    private static Path publicKey;

    @BeforeAll
    static void copyRealKeys() throws Exception {
        privateKey = copyKey("keys/private_key.pem", "private_key.pem");
        publicKey = copyKey("keys/public_key.pem", "public_key.pem");
        Set<PosixFilePermission> ownerOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(privateKey, ownerOnly);
        Files.setPosixFilePermissions(publicKey, ownerOnly);
    }

    private static Path copyKey(String resource, String fileName) throws Exception {
        try (InputStream in = ProdSecretsFailFastContextTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("test key %s on classpath", resource).isNotNull();
            Path target = keyDir.resolve(fileName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    @Test
    @DisplayName("weak POSTGRES_PASSWORD → only a [Security] warning: the validator passes, a server update is not blocked")
    void weakDbPasswordOnlyWarns(CapturedOutput output) {
        String weak = "keevo_local_pwd";
        Throwable thrown = catchStartupFailure(weak, VALID_ADMIN_PASSWORD);

        assertThat(findFailFast(thrown)).as("no [FailFast] in cause chain").isNull();
        assertThat(output.getAll())
                .contains("[Security] POSTGRES_PASSWORD")
                .contains("[FailFast] Production secrets validation passed.")
                .doesNotContain(weak);
    }

    @Test
    @DisplayName("weak ADMIN_PASSWORD → prod context refuses to start with [FailFast] ADMIN_PASSWORD")
    void weakAdminPasswordAbortsStartup() {
        String weak = "change_me_strong_password";
        Throwable thrown = catchStartupFailure(VALID_DB_PASSWORD, weak);

        IllegalStateException failFast = findFailFast(thrown);
        assertThat(failFast).as("[FailFast] IllegalStateException in cause chain").isNotNull();
        assertThat(failFast.getMessage()).contains("ADMIN_PASSWORD").doesNotContain(weak);
    }

    @Test
    @DisplayName("valid secrets → validator passes in-context; startup only fails later on the unreachable DB")
    void validSecretsPassValidatorThenFailOnDatabase(CapturedOutput output) {
        Throwable thrown = catchStartupFailure(VALID_DB_PASSWORD, VALID_ADMIN_PASSWORD);

        assertThat(output.getAll())
                .as("validator ran in-context and passed")
                .contains("[FailFast] Production secrets validation passed.");
        assertThat(findFailFast(thrown)).as("no [FailFast] in cause chain").isNull();
        assertThat(causeChain(thrown))
                .as("startup failed on the DB connection, i.e. after the validator passed")
                .anyMatch(SQLException.class::isInstance);
    }

    @Test
    @DisplayName("wassender with placeholder token → prod context refuses to start naming WASSENDER_API_TOKEN")
    void placeholderProviderTokenAbortsStartup() {
        String placeholder = "your_wassender_api_token_here";
        Throwable thrown = catchStartupFailure(VALID_DB_PASSWORD, VALID_ADMIN_PASSWORD, Map.of(
                "keevo.whatsapp.provider", "wassender",
                "keevo.whatsapp.wassender.api-url", "https://www.wasenderapi.com",
                "keevo.whatsapp.wassender.api-token", placeholder));

        IllegalStateException failFast = findFailFast(thrown);
        assertThat(failFast).as("[FailFast] IllegalStateException in cause chain").isNotNull();
        assertThat(failFast.getMessage()).contains("WASSENDER_API_TOKEN").doesNotContain(placeholder);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Throwable catchStartupFailure(String dbPassword, String adminPassword) {
        return catchStartupFailure(dbPassword, adminPassword, Map.of());
    }

    /** {@code extraProps} override the defaults below (e.g. a non-noop WhatsApp provider). */
    private static Throwable catchStartupFailure(String dbPassword, String adminPassword,
                                                 Map<String, String> extraProps) {
        Map<String, String> props = new HashMap<>(Map.ofEntries(
                Map.entry("spring.profiles.active", "prod"),
                Map.entry("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/x"),
                Map.entry("spring.datasource.username", "keevo_app"),
                Map.entry("spring.datasource.password", dbPassword),
                Map.entry("spring.datasource.hikari.connection-timeout", "500"),
                Map.entry("spring.datasource.hikari.initialization-fail-timeout", "-1"),
                Map.entry("spring.datasource.hikari.minimum-idle", "0"),
                Map.entry("spring.flyway.connect-retries", "0"),
                Map.entry("keevo.admin.phone", "+237600000000"),
                Map.entry("keevo.admin.password", adminPassword),
                Map.entry("keevo.jwt.private-key-path", "file:" + privateKey.toAbsolutePath()),
                Map.entry("keevo.jwt.public-key-path", "file:" + publicKey.toAbsolutePath()),
                Map.entry("keevo.whatsapp.provider", "noop"),
                Map.entry("keevo.fcm.enabled", "false"),
                Map.entry("spring.main.banner-mode", "off")));
        props.putAll(extraProps);
        List<String> args = new ArrayList<>();
        props.forEach((k, v) -> args.add("--" + k + "=" + v));

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(KeevoApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new))) {
            fail("prod context was expected to fail to start (unreachable DB), but it started");
            return null;
        } catch (Throwable t) {
            if (t instanceof AssertionError ae) {
                throw ae;
            }
            return t;
        }
    }

    private static IllegalStateException findFailFast(Throwable thrown) {
        return causeChain(thrown).stream()
                .filter(IllegalStateException.class::isInstance)
                .map(IllegalStateException.class::cast)
                .filter(e -> e.getMessage() != null && e.getMessage().contains("[FailFast]"))
                .findFirst()
                .orElse(null);
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
