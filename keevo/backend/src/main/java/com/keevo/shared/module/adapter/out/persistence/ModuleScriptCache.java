package com.keevo.shared.module.adapter.out.persistence;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 16.12 (2) — the migration scripts of the modules, read from the classpath <b>once per (root, module)</b> and
 * kept in memory, so that no Flyway run, {@code hasMigrations} or {@code latestVersion} touches the application jar
 * again. From the packaged Spring Boot jar, concurrent classpath scans of the same nested jar closed its shared handle
 * under one another ({@code Stream closed}, {@code Zip file closed}); with this cache only the first call per
 * (root, module) reads the jar, under the cache's lock (concurrent first callers wait for that single read, and two
 * modules are never read from the jar at the same time).
 *
 * <p>A failed read is never cached: the loader throws, the key stays absent and the next call reads again. The text of
 * a script is the exact (strict) UTF-8 decoding of its bytes — what Flyway's classpath resource reads — so checksums
 * are unchanged. Memory: the module scripts once (small text files), never a per-tenant copy.
 */
final class ModuleScriptCache {

    /** The JVM-wide cache over the application class loader (the production instance). */
    static final ModuleScriptCache CLASSPATH = new ModuleScriptCache(
            new PathMatchingResourcePatternResolver(ModuleScriptCache.class.getClassLoader()));

    /**
     * One {@code .sql} file of a module location (whatever its name: naming is validated by Flyway / the callers).
     *
     * @param root         classpath root ({@code db/modules})
     * @param moduleId     module id (folder under the root)
     * @param relativePath path under {@code <root>/<moduleId>/}, sub-folders kept ({@code V1__core_baseline.sql})
     * @param sql          the exact UTF-8 text of the file
     */
    record ModuleScript(String root, String moduleId, String relativePath, String sql) {

        /** File name (last segment of the relative path). */
        String filename() {
            return relativePath.substring(relativePath.lastIndexOf('/') + 1);
        }

        /** Classpath path of the file ({@code db/modules/core/V1__core_baseline.sql}). */
        String classpathPath() {
            return root + "/" + moduleId + "/" + relativePath;
        }
    }

    private final ResourcePatternResolver resolver;
    private final Map<String, List<ModuleScript>> byLocation = new ConcurrentHashMap<>();

    ModuleScriptCache(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    /** The scripts of a module across {@code roots}, in root order (one classpath read per root and module per cache). */
    List<ModuleScript> scripts(List<String> roots, String moduleId) {
        if (roots.size() == 1) {
            return location(roots.get(0), moduleId);
        }
        List<ModuleScript> all = new ArrayList<>();
        for (String root : roots) {
            all.addAll(location(root, moduleId));
        }
        return List.copyOf(all);
    }

    /**
     * The scripts of one location, read on first use; a read failure is not cached. Every fill holds the cache's
     * lock: a single classpath read at a time, even for different modules (concurrent scans of the packaged jar are
     * the defect this class removes); a filled location is then read without any lock.
     */
    List<ModuleScript> location(String root, String moduleId) {
        String key = root + "\n" + moduleId; // '\n' cannot appear in a root nor in a validated module id
        List<ModuleScript> known = byLocation.get(key);
        if (known == null) {
            synchronized (this) {
                known = byLocation.get(key);
                if (known == null) {
                    known = read(root, moduleId); // throws on failure: nothing cached, the next call reads again
                    byLocation.put(key, known);
                }
            }
        }
        return known;
    }

    private List<ModuleScript> read(String root, String moduleId) {
        String location = root + "/" + moduleId + "/";
        try {
            List<ModuleScript> scripts = new ArrayList<>();
            for (Resource resource : resolver.getResources("classpath*:" + location + "**/*.sql")) {
                if (!resource.exists()) {
                    continue;
                }
                String relativePath = relativePath(resource, location);
                byte[] bytes;
                try (InputStream in = resource.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                // strict decoding (malformed input fails, like Flyway's reader), no replacement character
                String sql = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                scripts.add(new ModuleScript(root, moduleId, relativePath, sql));
            }
            return List.copyOf(scripts);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan the migrations of a module", e);
        }
    }

    /**
     * Path of the resource under {@code location} (decoded, sub-folders kept, as Flyway's scan reports it). A URL
     * that does not contain the location fails the read: falling back to the bare file name would drop sub-folders
     * and change the script name recorded in the history.
     */
    static String relativePath(Resource resource, String location) throws IOException {
        String url = resource.getURL().toString();
        int at = url.lastIndexOf("/" + location);
        String relative = at < 0 ? "" : org.springframework.util.StringUtils.uriDecode(
                url.substring(at + location.length() + 1), StandardCharsets.UTF_8);
        if (relative.isEmpty() || relative.endsWith("/")) {
            throw new IOException("Module script outside its location " + location);
        }
        return relative;
    }
}
