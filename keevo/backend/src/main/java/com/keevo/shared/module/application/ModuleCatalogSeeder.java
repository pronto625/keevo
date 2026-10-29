package com.keevo.shared.module.application;

import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import com.keevo.shared.module.application.port.ModuleDefinitionPort;
import com.keevo.shared.module.application.port.ModuleDefinitionPort.UpsertOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Seeds the global module catalogue {@code public.module_definitions} at boot (Story 13.1, spec-socle §2.1).
 *
 * <p>Runs during bean initialisation, after Flyway ({@link DependsOnDatabaseInitialization}) and therefore before
 * the web server accepts traffic. The manifests are loaded (through the application context's resource resolver)
 * and validated first: an invalid set throws {@link com.keevo.shared.module.InvalidModuleManifestException}, boot
 * fails, and the catalogue is not touched. The whole seed then runs in one transaction (all or nothing): each
 * manifest is inserted if absent or updated when a seeded column changed ({@code installed_at} kept). A manifest
 * older than the stored row (rollback, rolling deploy of an older build) is skipped with a WARN — the catalogue is
 * never downgraded. Rows of modules no longer on the classpath are kept and logged at WARN — never deleted.
 */
@Component
@DependsOnDatabaseInitialization
public class ModuleCatalogSeeder implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ModuleCatalogSeeder.class);

    private static final Comparator<String> SEMVER_ORDER = Comparator.comparing(
            ModuleCatalogSeeder::semverParts, Arrays::compare);

    private final ModuleDefinitionPort catalogue;
    private final TransactionTemplate transaction;
    private final ResourcePatternResolver resources;

    public ModuleCatalogSeeder(ModuleDefinitionPort catalogue, PlatformTransactionManager transactionManager,
                               ResourcePatternResolver resources) {
        this.catalogue = catalogue;
        this.transaction = new TransactionTemplate(transactionManager);
        this.resources = resources;
    }

    /** What a seed run did, by module id. */
    public record SeedReport(List<String> inserted, List<String> updated, List<String> unchanged,
                             List<String> skippedOlder, Set<String> notOnClasspath) {
        public SeedReport {
            inserted = List.copyOf(inserted);
            updated = List.copyOf(updated);
            unchanged = List.copyOf(unchanged);
            skippedOlder = List.copyOf(skippedOlder);
            notOnClasspath = Set.copyOf(notOnClasspath);
        }

        /** Number of rows written (inserted + updated). */
        public int changedRows() {
            return inserted.size() + updated.size();
        }
    }

    @Override
    public void afterPropertiesSet() {
        seed(ModuleManifestLoader.loadFromClasspath(resources));
    }

    /** Upserts {@code manifests} (already validated) in one transaction and reports rows without a manifest. */
    public SeedReport seed(List<LoadedModuleManifest> manifests) {
        SeedReport report = transaction.execute(status -> seedInTransaction(manifests));
        if (report.changedRows() == 0) {
            log.info("Module catalogue unchanged — {} module(s) up to date", report.unchanged().size());
        } else {
            log.info("Module catalogue seeded — inserted={} updated={} unchanged={}",
                    report.inserted(), report.updated(), report.unchanged().size());
        }
        return report;
    }

    private SeedReport seedInTransaction(List<LoadedModuleManifest> manifests) {
        Map<String, String> stored = catalogue.findVersions();
        List<String> inserted = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> skippedOlder = new ArrayList<>();
        Set<String> seededIds = new TreeSet<>();
        for (LoadedModuleManifest loaded : manifests) {
            String id = loaded.manifest().id();
            String version = loaded.manifest().version();
            seededIds.add(id);
            String storedVersion = stored.get(id);
            if (storedVersion != null && SEMVER_ORDER.compare(version, storedVersion) < 0) {
                log.warn("Module catalogue: module '{}' manifest version {} is older than the registered version {} "
                        + "— row kept, not downgraded", id, version, storedVersion);
                skippedOlder.add(id);
                continue;
            }
            UpsertOutcome outcome = catalogue.upsert(loaded);
            switch (outcome) {
                case INSERTED -> inserted.add(id);
                case UPDATED -> updated.add(id);
                case UNCHANGED -> unchanged.add(id);
            }
            log.debug("Module catalogue: '{}' {} (version {})", id, outcome.name().toLowerCase(), version);
        }
        Set<String> notOnClasspath = new TreeSet<>(stored.keySet());
        notOnClasspath.removeAll(seededIds);
        for (String orphan : notOnClasspath) {
            log.warn("Module catalogue: module '{}' is registered in public.module_definitions but has no manifest "
                    + "on the classpath — row kept", orphan);
        }
        return new SeedReport(inserted, updated, unchanged, skippedOlder, notOnClasspath);
    }

    /** {@code MAJOR.MINOR.PATCH} → its three numbers (both sides are validated semver: manifest + column CHECK). */
    static int[] semverParts(String version) {
        return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }
}
