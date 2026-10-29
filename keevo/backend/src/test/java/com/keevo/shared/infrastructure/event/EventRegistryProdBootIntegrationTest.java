package com.keevo.shared.infrastructure.event;

import com.keevo.KeevoApplication;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 12.4 — the application boots under the {@code prod} profile (Hibernate {@code ddl-auto=validate},
 * Flyway on a pristine database) with the event publication registry: Flyway V28 creates
 * {@code public.event_publication} exactly as Spring Modulith 1.4 expects, Flyway validates, the registry
 * properties of {@code application.yml} are in force; and a database without the table fails fast at boot.
 *
 * <p>Same technique as {@code ProdSecretsFailFastContextTest}: {@link SpringApplicationBuilder}, every
 * property as a command-line argument (beats a local {@code .env}), here against a dedicated database of the
 * shared Testcontainers PostgreSQL.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventRegistryProdBootIntegrationTest {

    private static final String DB = "keevo_event_registry_prod";

    @TempDir
    static Path keyDir;

    private static String url;
    private static Path privateKey;
    private static Path publicKey;

    @BeforeAll
    static void setUp() throws Exception {
        url = SharedPostgresContainer.freshDatabase(DB);
        privateKey = copyKey("keys/private_key.pem", "private_key.pem");
        publicKey = copyKey("keys/public_key.pem", "public_key.pem");
        Set<PosixFilePermission> ownerOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(privateKey, ownerOnly);   // prod validator: key never world-readable
        Files.setPosixFilePermissions(publicKey, ownerOnly);
    }

    @AfterAll
    static void dropDatabase() {
        SharedPostgresContainer.dropDatabase(DB);
    }

    private static Path copyKey(String resource, String fileName) throws Exception {
        try (InputStream in = EventRegistryProdBootIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("test key %s on classpath", resource).isNotNull();
            Path target = keyDir.resolve(fileName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    private static ConfigurableApplicationContext bootProd() {
        Map<String, String> props = Map.ofEntries(
                Map.entry("spring.profiles.active", "prod"),
                Map.entry("spring.datasource.url", url),
                Map.entry("spring.datasource.username", SharedPostgresContainer.username()),
                Map.entry("spring.datasource.password", SharedPostgresContainer.password()),
                Map.entry("spring.datasource.hikari.maximum-pool-size", "4"),
                Map.entry("spring.datasource.hikari.minimum-idle", "0"),
                Map.entry("keevo.admin.phone", "+237600000000"),
                Map.entry("keevo.admin.password", "Str0ngPr0dP@ss!"),
                Map.entry("keevo.jwt.private-key-path", "file:" + privateKey.toAbsolutePath()),
                Map.entry("keevo.jwt.public-key-path", "file:" + publicKey.toAbsolutePath()),
                Map.entry("keevo.whatsapp.provider", "noop"),
                Map.entry("keevo.fcm.enabled", "false"),
                Map.entry("keevo.scheduling.enabled", "false"),
                Map.entry("keevo.product-photo.storage-dir", keyDir.resolve("photos").toString()),
                Map.entry("keevo.export.storage-dir", keyDir.resolve("exports").toString()),
                Map.entry("spring.main.banner-mode", "off"));
        List<String> args = new ArrayList<>();
        props.forEach((k, v) -> args.add("--" + k + "=" + v));
        return new SpringApplicationBuilder(KeevoApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password()));
    }

    @Test
    @Order(1)
    @DisplayName("prod profile boots: Flyway validates, public.event_publication has the Modulith 1.4 shape")
    void prodBootValidatesTheRegistryTable() {
        try (ConfigurableApplicationContext ctx = bootProd()) {
            ctx.getBean(Flyway.class).validate();   // throws on any checksum / pending mismatch
            assertThat(ctx.getBean(Flyway.class).info().pending()).isEmpty();

            Environment env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.modulith.events.jdbc.schema")).isEqualTo("public");
            assertThat(env.getProperty("spring.modulith.events.jdbc.schema-initialization.enabled")).isEqualTo("false");
            assertThat(env.getProperty("spring.modulith.events.republish-outstanding-events-on-restart")).isEqualTo("true");
            assertThat(env.getProperty("spring.modulith.events.completion-mode")).isEqualToIgnoringCase("update");
        }

        List<String> columns = jdbc().queryForList("SELECT column_name || ':' || data_type || ':' || is_nullable "
                + "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'event_publication' "
                + "ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly(
                "id:uuid:NO",
                "listener_id:text:NO",
                "event_type:text:NO",
                "serialized_event:text:NO",
                "publication_date:timestamp with time zone:NO",
                "completion_date:timestamp with time zone:YES");
        assertThat(jdbc().queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' "
                + "AND tablename = 'event_publication'", String.class))
                .contains("event_publication_serialized_event_hash_idx", "event_publication_by_completion_date_idx");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = 'event_listener_receipt'", Integer.class)).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("registry table missing → the prod boot fails fast naming the table")
    void missingTableFailsFast() {
        jdbc().execute("DROP TABLE public.event_publication");

        assertThatThrownBy(EventRegistryProdBootIntegrationTest::bootProd)
                .satisfies(t -> {
                    List<String> messages = new ArrayList<>();
                    for (Throwable c = t; c != null && !messages.contains(String.valueOf(c.getMessage())); c = c.getCause()) {
                        messages.add(String.valueOf(c.getMessage()));
                    }
                    assertThat(messages).anyMatch(m -> m.contains("[FailFast] Table public.event_publication is missing"));
                });
    }
}
