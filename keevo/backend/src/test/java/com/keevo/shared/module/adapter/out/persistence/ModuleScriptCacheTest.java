package com.keevo.shared.module.adapter.out.persistence;

import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 16.12 (2) — module scripts read from the classpath once, served to Flyway from memory. */
class ModuleScriptCacheTest {

    private static final List<String> ROOTS = List.of("db/modules", "db/modules-next");

    @TempDir
    Path classpath;

    private URLClassLoader loader;
    private CountingResolver resolver;
    private ModuleScriptCache cache;

    @BeforeEach
    void setUp() throws IOException {
        write("db/modules/two/V1__two_items.sql", "CREATE TABLE items (id INT);\n");
        write("db/modules/two/sub/V2__two_label.sql", "ALTER TABLE items ADD label TEXT; -- libellé, créé\n");
        write("db/modules-next/two/V3__two_more.sql", "ALTER TABLE items ADD qty INT;\n");
        write("db/modules/onlybad/notes.sql", "SELECT 1;\n");
        write("db/modules/mixed/V1__mixed_ok.sql", "SELECT 1;\n");
        write("db/modules/mixed/bad.sql", "SELECT 2;\n");
        loader = new URLClassLoader(new URL[]{classpath.toUri().toURL()}, null);
        resolver = new CountingResolver(new PathMatchingResourcePatternResolver(loader));
        cache = new ModuleScriptCache(resolver);
    }

    @AfterEach
    void tearDown() throws IOException {
        loader.close();
    }

    private void write(String path, String text) throws IOException {
        Path file = classpath.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String pattern(String root, String moduleId) {
        return "classpath*:" + root + "/" + moduleId + "/**/*.sql";
    }

    @Test
    @DisplayName("8 concurrent first calls → one classpath read per root and module")
    void concurrentFirstCalls() throws Exception {
        resolver.delayMillis = 50; // widen the race window
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<ModuleScriptCache.ModuleScript>>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return cache.scripts(ROOTS, "two");
                }));
            }
            start.countDown();
            for (Future<List<ModuleScriptCache.ModuleScript>> call : calls) {
                assertThat(call.get()).hasSize(3);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(resolver.calls).containsOnlyKeys(pattern("db/modules", "two"), pattern("db/modules-next", "two"));
        assertThat(resolver.calls.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        cache.scripts(ROOTS, "two"); // later calls never read again
        assertThat(resolver.calls.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
    }

    @Test
    @DisplayName("a failed read is not cached: same UncheckedIOException message, the next call reads again")
    void failureNotCached() {
        resolver.failures = 1;
        assertThatThrownBy(() -> cache.scripts(List.of("db/modules"), "two"))
                .isInstanceOf(UncheckedIOException.class).hasMessage("Cannot scan the migrations of a module");
        assertThat(cache.scripts(List.of("db/modules"), "two")).hasSize(2);
        assertThat(resolver.calls.get(pattern("db/modules", "two")).get()).isEqualTo(2);

        FlywayModuleMigrations migrations = new FlywayModuleMigrations(
                new DriverManagerDataSource("jdbc:postgresql://unused/db"), List.of("db/modules"), cache);
        resolver.failures = 1;
        assertThatThrownBy(() -> migrations.hasMigrations("mixed"))
                .isInstanceOf(UncheckedIOException.class).hasMessage("Cannot scan the migrations of a module");
        assertThat(migrations.hasMigrations("mixed")).isTrue();
    }

    @Test
    @DisplayName("a script whose URL is outside its location fails the read (never the bare file name), not cached")
    void scriptOutsideItsLocation() throws IOException {
        Path stray = classpath.resolve("elsewhere/sub/V1__stray.sql");
        Files.createDirectories(stray.getParent());
        Files.writeString(stray, "SELECT 1;", StandardCharsets.UTF_8);
        Resource resource = new org.springframework.core.io.UrlResource(stray.toUri());
        assertThatThrownBy(() -> ModuleScriptCache.relativePath(resource, "db/modules/two/"))
                .isInstanceOf(IOException.class).hasMessageContaining("db/modules/two/");
        assertThat(ModuleScriptCache.relativePath(new org.springframework.core.io.UrlResource(
                classpath.resolve("db/modules/two/sub/V2__two_label.sql").toUri()), "db/modules/two/"))
                .isEqualTo("sub/V2__two_label.sql");

        ModuleScriptCache strayCache = new ModuleScriptCache(new CountingResolver(new PathMatchingResourcePatternResolver(loader)) {
            @Override
            public Resource[] getResources(String locationPattern) throws IOException {
                return new Resource[]{resource};
            }
        });
        assertThatThrownBy(() -> strayCache.scripts(List.of("db/modules"), "two"))
                .isInstanceOf(UncheckedIOException.class).hasMessage("Cannot scan the migrations of a module")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("unreadable scripts at migrate() → ModuleProvisioningException SCRIPTS/…, before any connection")
    void migrateScriptReadFailure() {
        FlywayModuleMigrations migrations = new FlywayModuleMigrations(
                new DriverManagerDataSource("jdbc:postgresql://unused/db"), List.of("db/modules"), cache);
        resolver.failures = 1;
        assertThatThrownBy(() -> migrations.migrate("kv_abc123", "two"))
                .isInstanceOfSatisfying(com.keevo.shared.module.ModuleProvisioningException.class,
                        e -> {
                            assertThat(e.failure()).isEqualTo("SCRIPTS/UncheckedIOException");
                            assertThat(e.rootCause()).startsWith("IOException: simulated");
                        });
    }

    @Test
    @DisplayName("two roots merged in root order; relative paths keep sub-folders; exact UTF-8 text (accents)")
    void twoRootsMerged() throws IOException {
        List<ModuleScriptCache.ModuleScript> scripts = cache.scripts(ROOTS, "two");
        assertThat(scripts).extracting(ModuleScriptCache.ModuleScript::relativePath)
                .containsExactlyInAnyOrder("V1__two_items.sql", "sub/V2__two_label.sql", "V3__two_more.sql");
        assertThat(scripts.get(scripts.size() - 1).root()).isEqualTo("db/modules-next");
        ModuleScriptCache.ModuleScript label = scripts.stream()
                .filter(s -> s.relativePath().equals("sub/V2__two_label.sql")).findFirst().orElseThrow();
        assertThat(label.filename()).isEqualTo("V2__two_label.sql");
        assertThat(label.classpathPath()).isEqualTo("db/modules/two/sub/V2__two_label.sql");
        assertThat(label.sql()).isEqualTo(Files.readString(
                classpath.resolve("db/modules/two/sub/V2__two_label.sql"), StandardCharsets.UTF_8))
                .contains("libellé, créé");

        FlywayModuleMigrations migrations = new FlywayModuleMigrations(
                new DriverManagerDataSource("jdbc:postgresql://unused/db"), ROOTS, cache);
        assertThat(migrations.latestVersion("two")).isEqualTo("3");
        assertThat(migrations.hasMigrations("two")).isTrue();
    }

    @Test
    @DisplayName("only misnamed files → no migrations; misnamed beside valid → still listed (Flyway refuses it)")
    void misnamed() {
        FlywayModuleMigrations migrations = new FlywayModuleMigrations(
                new DriverManagerDataSource("jdbc:postgresql://unused/db"), List.of("db/modules"), cache);
        assertThat(migrations.hasMigrations("onlybad")).isFalse();
        assertThat(migrations.latestVersion("onlybad")).isNull();
        assertThat(migrations.hasMigrations("absent")).isFalse();
        assertThat(cache.scripts(List.of("db/modules"), "mixed"))
                .extracting(ModuleScriptCache.ModuleScript::filename)
                .containsExactlyInAnyOrder("V1__mixed_ok.sql", "bad.sql");
    }

    @Test
    @DisplayName("malformed UTF-8 fails the read (strict decoding, as Flyway's reader), not cached")
    void malformedUtf8() throws IOException {
        Path file = classpath.resolve("db/modules/latin/V1__latin.sql");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{'S', 'E', 'L', 'E', 'C', 'T', ' ', (byte) 0xE9, ';'});
        assertThatThrownBy(() -> cache.scripts(List.of("db/modules"), "latin"))
                .isInstanceOf(UncheckedIOException.class).hasMessage("Cannot scan the migrations of a module");
        Files.writeString(file, "SELECT 'é';", StandardCharsets.UTF_8);
        assertThat(cache.scripts(List.of("db/modules"), "latin")).singleElement()
                .extracting(ModuleScriptCache.ModuleScript::sql).isEqualTo("SELECT 'é';");
    }

    @Test
    @DisplayName("the JVM cache reads the real module scripts byte-exact (same name and relative path as Flyway's scan)")
    void realClasspath() throws IOException {
        List<ModuleScriptCache.ModuleScript> core = ModuleScriptCache.CLASSPATH.scripts(List.of("db/modules"), "core");
        assertThat(core).extracting(ModuleScriptCache.ModuleScript::relativePath).contains("V1__core_baseline.sql");
        for (ModuleScriptCache.ModuleScript script : core) {
            Resource original = new PathMatchingResourcePatternResolver().getResource(
                    "classpath:" + script.classpathPath());
            assertThat(script.sql()).isEqualTo(original.getContentAsString(StandardCharsets.UTF_8));
        }
        assertThat(ModuleScriptCache.CLASSPATH.scripts(List.of("db/modules"), "core")).isSameAs(core);
    }

    @Test
    @DisplayName("Flyway resource provider: prefix/suffix filter on the file name, lookup by relative path")
    void resourceProvider() throws IOException {
        InMemoryModuleResourceProvider provider = new InMemoryModuleResourceProvider(cache.scripts(ROOTS, "two"));
        assertThat(provider.getResources("V", new String[]{".sql"})).extracting(LoadableResource::getFilename)
                .containsExactlyInAnyOrder("V1__two_items.sql", "V2__two_label.sql", "V3__two_more.sql");
        assertThat(provider.getResources("", new String[]{".SQL"})).hasSize(3);
        assertThat(provider.getResources("R", new String[]{".sql"})).isEmpty();
        assertThat(provider.getResources("beforeMigrate", new String[]{".sql"})).isEmpty();
        LoadableResource label = provider.getResource("SUB/v2__two_label.sql");
        assertThat(label).isNotNull();
        assertThat(label.getRelativePath()).isEqualTo("sub/V2__two_label.sql");
        assertThat(label.getAbsolutePath()).isEqualTo("db/modules/two/sub/V2__two_label.sql");
        try (Reader reader = label.read()) {
            assertThat(new java.io.BufferedReader(reader).lines().toList()).first().asString().contains("créé");
        }
        assertThat(provider.getResource("V9__none.sql")).isNull();
        assertThat(InMemoryModuleResourceProvider.NO_JAVA_MIGRATIONS.getClasses()).isEmpty();
        // Flyway's rule: the name must be longer than prefix + suffix
        assertThat(InMemoryModuleResourceProvider.startsAndEndsWith("V.sql", "V", ".sql")).isFalse();
    }

    /** Delegating resolver counting {@code getResources} per pattern, with an optional delay and failures. */
    private static class CountingResolver implements ResourcePatternResolver {
        private final ResourcePatternResolver delegate;
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        volatile long delayMillis;
        volatile int failures;

        CountingResolver(ResourcePatternResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Resource[] getResources(String locationPattern) throws IOException {
            calls.computeIfAbsent(locationPattern, k -> new AtomicInteger()).incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failures > 0) {
                failures--;
                throw new IOException("simulated: Stream closed");
            }
            return delegate.getResources(locationPattern);
        }

        @Override
        public Resource getResource(String location) {
            return delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }
    }
}
