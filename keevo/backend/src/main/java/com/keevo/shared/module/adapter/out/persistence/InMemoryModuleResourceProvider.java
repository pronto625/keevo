package com.keevo.shared.module.adapter.out.persistence;

import org.flywaydb.core.api.ClassProvider;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.resource.LoadableResource;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Story 16.12 (2) — Flyway's {@link ResourceProvider} over a module's scripts held by {@link ModuleScriptCache}: with
 * it and {@link #NO_JAVA_MIGRATIONS}, Flyway does not create its classpath scanner, so a migration run reads nothing
 * from the application jar. Flyway's own parser, checksum and validation (naming, checksums, duplicate versions) are
 * unchanged: each resource reports the file name and the relative path the classpath scan reported, and reads the
 * same text.
 */
final class InMemoryModuleResourceProvider implements ResourceProvider {

    /** Module migrations are SQL only: no Java migration class is ever looked up. */
    static final ClassProvider<JavaMigration> NO_JAVA_MIGRATIONS = List::of;

    private final List<LoadableResource> resources;

    InMemoryModuleResourceProvider(List<ModuleScriptCache.ModuleScript> scripts) {
        List<LoadableResource> all = new ArrayList<>(scripts.size());
        scripts.forEach(script -> all.add(new ScriptResource(script)));
        this.resources = List.copyOf(all);
    }

    /** By relative path, case-insensitive (Flyway's scanner semantics); {@code null} when unknown. */
    @Override
    public LoadableResource getResource(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (LoadableResource resource : resources) {
            if (resource.getRelativePath().toLowerCase(Locale.ROOT).equals(wanted)) {
                return resource;
            }
        }
        return null;
    }

    /** File name starting with {@code prefix} and ending with one of {@code suffixes} (Flyway's scanner filter). */
    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        List<LoadableResource> result = new ArrayList<>();
        for (LoadableResource resource : resources) {
            if (startsAndEndsWith(resource.getFilename(), prefix, suffixes)) {
                result.add(resource);
            }
        }
        return result;
    }

    /** Same rule as Flyway's {@code StringUtils.startsAndEndsWith} (prefix case-sensitive, suffix not). */
    static boolean startsAndEndsWith(String name, String prefix, String... suffixes) {
        if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        String head = prefix == null ? "" : prefix;
        for (String suffix : suffixes) {
            if (upper.endsWith(suffix.toUpperCase(Locale.ROOT)) && name.length() > (head + suffix).length()) {
                return true;
            }
        }
        return false;
    }

    /** One cached script as a Flyway resource (immutable text, a fresh reader per read). */
    static final class ScriptResource extends LoadableResource {

        private final ModuleScriptCache.ModuleScript script;

        ScriptResource(ModuleScriptCache.ModuleScript script) {
            this.script = script;
        }

        @Override
        public Reader read() {
            return new StringReader(script.sql());
        }

        @Override
        public String getAbsolutePath() {
            return script.classpathPath();
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return script.classpathPath(); // in memory: no file on disk
        }

        @Override
        public String getFilename() {
            return script.filename();
        }

        @Override
        public String getRelativePath() {
            return script.relativePath();
        }
    }
}
