package com.keevo.shared.module;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 16.12 — the versioned migration scripts of the foundation modules ({@link ModuleOrder#isFoundation}), in
 * {@link ModuleOrder} order then version order: the single source of the tenant tables. The registration executes
 * them inside its transaction ({@code TenantSchemaProvisioner}); Flyway then re-applies them per module after commit
 * (they are idempotent forever) and records the history. Immutable; {@link #fromClasspath()} is read once per JVM.
 *
 * @param scripts every foundation script, in execution order
 */
public record FoundationScripts(List<Script> scripts) {

    /** Classpath root of the module migrations (same as {@code FlywayModuleMigrations.DEFAULT_ROOT}). */
    public static final String ROOT = "db/modules";

    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    /**
     * One versioned script of a foundation module.
     *
     * @param moduleId the owning module
     * @param version  Flyway version of the script ({@code 1}, {@code 2.1}…)
     * @param name     file name ({@code V1__core_baseline.sql})
     * @param sql      the script text (UTF-8)
     */
    public record Script(String moduleId, String version, String name, String sql) {
    }

    public FoundationScripts {
        scripts = List.copyOf(scripts);
    }

    /** The foundation modules shipping scripts, in execution order. */
    public List<String> moduleIds() {
        return scripts.stream().map(Script::moduleId).distinct().toList();
    }

    /** Foundation module → its latest script version, in execution order. */
    public Map<String, String> latestVersions() {
        Map<String, String> latest = new LinkedHashMap<>();
        scripts.forEach(s -> latest.put(s.moduleId(), s.version())); // version order within a module
        return latest;
    }

    private static volatile FoundationScripts classpathScripts;

    /**
     * The foundation scripts of this build (manifests and scripts on the classpath), read once. Read eagerly at
     * startup by the {@code TenantSchemaProvisioner} bean: an invalid set fails the boot with a clear message (and is
     * read again on the next call, never cached as a failure).
     *
     * @throws IllegalStateException the scripts cannot be read, a version is shipped twice with different contents,
     *                               or a {@code .sql} file of a foundation module is not Flyway-named
     */
    public static FoundationScripts fromClasspath() {
        FoundationScripts known = classpathScripts;
        if (known == null) {
            synchronized (FoundationScripts.class) {
                known = classpathScripts;
                if (known == null) {
                    try {
                        ResourcePatternResolver resolver =
                                new PathMatchingResourcePatternResolver(FoundationScripts.class.getClassLoader());
                        known = load(ModuleManifestLoader.loadFromClasspath(resolver).stream()
                                .map(LoadedModuleManifest::manifest).toList(), resolver);
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("Foundation module migrations unreadable (db/modules/<id>): "
                                + e.getMessage(), e);
                    }
                    classpathScripts = known;
                }
            }
        }
        return known;
    }

    /** Reads the foundation scripts of {@code modules} (in {@link ModuleOrder} order) through {@code resolver}. */
    public static FoundationScripts load(List<ModuleManifest> modules, ResourcePatternResolver resolver) {
        List<Script> scripts = new ArrayList<>();
        for (ModuleManifest module : ModuleOrder.order(modules)) {
            if (ModuleOrder.isFoundation(module)) {
                scripts.addAll(moduleScripts(module.id(), resolver));
            }
        }
        return new FoundationScripts(scripts);
    }

    private static List<Script> moduleScripts(String moduleId, ResourcePatternResolver resolver) {
        Map<MigrationVersion, Script> byVersion = new java.util.TreeMap<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:" + ROOT + "/" + moduleId + "/**/*.sql")) {
                String name = resource.getFilename();
                Matcher m = name == null ? null : VERSIONED.matcher(name);
                if (m == null || !m.matches()) {
                    // Flyway would refuse it too (validateMigrationNaming): never skipped silently
                    throw new IllegalStateException("Misnamed migration of foundation module " + moduleId + ": "
                            + name + " (expected V<version>__<description>.sql)");
                }
                Script script;
                try (InputStream in = resource.getInputStream()) {
                    script = new Script(moduleId, m.group(1).replace('_', '.'), name,
                            new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                Script previous = byVersion.putIfAbsent(MigrationVersion.fromVersion(script.version()), script);
                if (previous != null && !previous.sql().equals(script.sql())) {
                    throw new IllegalStateException("Foundation module " + moduleId + " ships version "
                            + script.version() + " twice with different contents (" + previous.name() + ", " + name
                            + ")");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the migrations of a foundation module", e);
        }
        return new ArrayList<>(byVersion.values());
    }
}
