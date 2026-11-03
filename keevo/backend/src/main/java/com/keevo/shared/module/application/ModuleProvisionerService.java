package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.job.ProgressListener;
import org.flywaydb.core.api.MigrationVersion;
import com.keevo.shared.module.FoundationUnavailableException;
import com.keevo.shared.module.FoundationUpgrade;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleOrder;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleNotActiveException;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ProvisioningReport;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.TenantNotFoundException;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort.LiveTenant;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ModuleProvisioner} of the core (Story 13.4): validates the input, checks that the module may be provisioned
 * for the tenant (row present, or active through the free rule), runs the module's migrations
 * ({@link ModuleMigrationPort}) and records {@code tenant_modules.provisioned_version}
 * ({@link TenantModuleProvisioningPort}), then evicts the registry's request cache for the tenant.
 */
@Service
public class ModuleProvisionerService implements ModuleProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ModuleProvisionerService.class);

    private final ModuleCatalog catalog;
    private final ModuleRegistry registry;
    private final TenantModuleStatePort states;
    private final TenantModuleProvisioningPort rows;
    private final ModuleMigrationPort migrations;
    private final KeevoModuleDirectory modules;
    private final TransactionOperations moduleTransaction;
    /** Story 14.1 — ensureProvisioned caches: (schema/module/latest version) already checked, per-key locks. */
    private final java.util.Set<String> ensured = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Object> ensureLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Boolean> shipsMigrations = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Optional<String>> latestVersions = new java.util.concurrent.ConcurrentHashMap<>();
    /** Story 16.12 — schemas whose foundation tables are known to be at the shipped versions (this JVM). */
    private final java.util.Set<String> foundationEnsured = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Story 16.12 — per-schema locks (striped: bounded memory) shared by every provisioning of a tenant. */
    private final java.util.concurrent.locks.ReentrantLock[] schemaLocks = new java.util.concurrent.locks.ReentrantLock[64];
    /** Story 16.12 — tenants being provisioned at the same time, over the request path, boot and registration. */
    private volatile java.util.concurrent.Semaphore provisioningSlots = new java.util.concurrent.Semaphore(4);
    /** Story 16.12 — request-path failures: no new attempt (503 at once) until {@code retryAt}, backoff doubling. */
    private final Map<String, Backoff> foundationFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<ModuleManifest> order;
    private volatile List<ModuleManifest> foundationModules;
    /** Clock of the backoff (nanoseconds); tests replace it. */
    java.util.function.LongSupplier nanoClock = System::nanoTime;

    static final long REQUEST_LOCK_WAIT_MS = 2_000;
    static final long FIRST_BACKOFF_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    static final long MAX_BACKOFF_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);

    private record Backoff(long retryAt, long delay) {
    }

    {
        for (int i = 0; i < schemaLocks.length; i++) {
            schemaLocks[i] = new java.util.concurrent.locks.ReentrantLock();
        }
    }

    /** Story 16.12 — tenants provisioned at the same time ({@code keevo.tenant-schema.upgrade-parallelism}). */
    @Autowired(required = false)
    public void setUpgradeParallelism(
            @org.springframework.beans.factory.annotation.Value("${keevo.tenant-schema.upgrade-parallelism:4}")
            int parallelism) {
        provisioningSlots = new java.util.concurrent.Semaphore(Math.max(1, parallelism));
    }

    /** Without module beans (no existing-schema baseline) nor transaction manager — tests. */
    public ModuleProvisionerService(ModuleCatalog catalog, ModuleRegistry registry, TenantModuleStatePort states,
                                    TenantModuleProvisioningPort rows, ModuleMigrationPort migrations) {
        this(catalog, registry, states, rows, migrations, KeevoModuleDirectory.empty(catalog),
                TransactionOperations.withoutTransaction());
    }

    /**
     * @param moduleTransaction how {@link #provisionActiveModules} isolates each module (production: a
     *                          {@code REQUIRES_NEW} transaction per module)
     */
    public ModuleProvisionerService(ModuleCatalog catalog, ModuleRegistry registry, TenantModuleStatePort states,
                                    TenantModuleProvisioningPort rows, ModuleMigrationPort migrations,
                                    KeevoModuleDirectory modules, TransactionOperations moduleTransaction) {
        this.catalog = catalog;
        this.registry = registry;
        this.states = states;
        this.rows = rows;
        this.migrations = migrations;
        this.modules = modules;
        this.moduleTransaction = moduleTransaction;
    }

    @Autowired
    public ModuleProvisionerService(ModuleCatalog catalog, ModuleRegistry registry, TenantModuleStatePort states,
                                    TenantModuleProvisioningPort rows, ModuleMigrationPort migrations,
                                    KeevoModuleDirectory modules, PlatformTransactionManager transactionManager) {
        this(catalog, registry, states, rows, migrations, modules, requiresNew(transactionManager));
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Override
    public boolean hasMigrations(String moduleId) {
        return migrations.hasMigrations(requireModule(moduleId).id());
    }

    @Override
    public ProvisioningResult provision(String tenantSchema, String moduleId) {
        // the platform schema "public" fails the tenant format too: never provisioned
        String schema = TenantSchema.validate(tenantSchema);
        ModuleManifest module = requireModule(moduleId);
        String id = module.id();
        if (!migrations.hasMigrations(id)) {
            return new ProvisioningResult(id, null, null, 0);
        }
        Map<String, TenantModuleStatePort.Row> tenantRows = states.findRows(schema)
                .orElseThrow(TenantNotFoundException::new);
        boolean rowExists = tenantRows.containsKey(id);
        // Story 16.12: the foundation tables exist for every tenant, whatever its module states
        if (!rowExists && !module.isCore() && !ModuleOrder.isFoundation(module) && !registry.isActive(schema, id)) {
            throw new ModuleNotActiveException(id);
        }

        ModuleMigrationPort.Outcome outcome;
        try {
            outcome = migrations.migrate(schema, id);
        } catch (ModuleProvisioningException e) {
            // earlier scripts of the run are committed: record what is really in the schema, then rethrow
            recordVersion(schema, module, rowExists, e.highestAppliedVersion());
            throw e;
        }
        recordVersion(schema, module, rowExists, outcome.toVersion());
        if (outcome.applied() > 0) {
            log.info("Module '{}' provisioned: {} migration(s), version {} → {}", id, outcome.applied(),
                    outcome.fromVersion(), outcome.toVersion());
        }
        return new ProvisioningResult(id, outcome.fromVersion(), outcome.toVersion(), outcome.applied());
    }

    /**
     * Writes {@code provisioned_version}: update of the existing row, else (non-core) insert of an ACTIVE/FREE row.
     * A {@code null} version (nothing versioned applied, e.g. repeatable scripts only) writes nothing — never a row
     * without a version. A write that touches no row (a higher version stored by another node, or the tenant row
     * gone) logs a WARN. A database failure becomes a {@link ModuleProvisioningException}.
     */
    private void recordVersion(String schema, ModuleManifest module, boolean rowExists, String version) {
        String id = module.id();
        // Story 14.1: no row for a paid module → nothing inserted (an ACTIVE/FREE row without expiry would grant it
        // for ever, e.g. when tenant creation provisions it for the trial); the Flyway history is the record.
        if (version == null || (!rowExists && (module.isCore() || !module.free()))) {
            registry.evict(schema);
            return;
        }
        int written;
        try {
            java.util.function.IntSupplier write = () -> rowExists ? rows.updateProvisionedVersion(schema, id, version)
                    : rows.insertActiveFree(schema, id, version);
            // Story 16.5: lazy provisioning may run inside a read-only transaction (e.g. a sync pull) where the row
            // write would fail — now that paid modules have rows. A read-only caller has written nothing, so a
            // separate transaction cannot wait on its locks.
            written = TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ? java.util.Objects.requireNonNullElse(moduleTransaction.execute(st -> write.getAsInt()), 0)
                    : write.getAsInt();
        } catch (DataAccessException | org.springframework.transaction.TransactionException e) {
            // TransactionException: the separate transaction of the read-only branch could not be started
            throw new ModuleProvisioningException(id, schema, "ROW_WRITE/" + e.getClass().getSimpleName(), version);
        } finally {
            registry.evict(schema);
        }
        if (written == 0) {
            log.warn("Module '{}': provisioned_version {} not recorded (a higher version is already stored, or the "
                    + "tenant row is gone)", id, version);
        }
    }

    @Override
    public void ensureProvisioned(String tenantSchema, String moduleId) {
        if (!TenantSchema.isValid(tenantSchema) || moduleId == null || !catalog.contains(moduleId)) {
            return;
        }
        String id = moduleId;
        if (!shipsMigrations.computeIfAbsent(id, migrations::hasMigrations)) {
            return;
        }
        String latest = latestVersions.computeIfAbsent(id, k -> Optional.ofNullable(migrations.latestVersion(k)))
                .orElse(null);
        String key = tenantSchema + '/' + id + '/' + latest;
        if (ensured.contains(key)) {
            return;
        }
        Object lock = ensureLocks.computeIfAbsent(tenantSchema + '/' + id, k -> new Object());
        synchronized (lock) {
            if (ensured.contains(key)) {
                return;
            }
            try {
                if (!migrations.schemaExists(tenantSchema)) {
                    // tenant row without its schema yet (registration in progress): nothing to provision, not cached
                    return;
                }
                String current = migrations.currentVersion(tenantSchema, id);
                if (latest == null || current == null
                        || MigrationVersion.fromVersion(current).compareTo(MigrationVersion.fromVersion(latest)) < 0) {
                    ProvisioningResult result = provision(tenantSchema, id);
                    log.info("Module '{}' provisioned on first use for tenant {} (version {} → {})", id,
                            tenantCode(tenantSchema), result.fromVersion(), result.toVersion());
                }
            } catch (RuntimeException e) {
                // our messages carry no schema value nor SQL text; the tenant is named by its code
                log.error("Module '{}' could not be provisioned on first use for tenant {} ({})", id,
                        tenantCode(tenantSchema), e.getClass().getSimpleName());
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                        "Module tables could not be provisioned; retry later");
            }
            ensured.add(key);
        }
    }

    // ── Story 16.12 — foundation tables ─────────────────────────────────────

    /** The foundation modules that ship migrations, in {@link ModuleOrder}. */
    private List<ModuleManifest> foundationModules() {
        List<ModuleManifest> known = foundationModules;
        if (known == null) {
            known = dependencyOrder().stream()
                    .filter(ModuleOrder::isFoundation)
                    .filter(m -> shipsMigrations(m.id()))
                    .toList();
            foundationModules = known;
        }
        return known;
    }

    private List<String> foundationModuleIds() {
        return foundationModules().stream().map(ModuleManifest::id).toList();
    }

    private boolean shipsMigrations(String moduleId) {
        return shipsMigrations.computeIfAbsent(moduleId, migrations::hasMigrations);
    }

    /** The module's latest shipped version, read once ({@code null}: unknown — the caller provisions to find out). */
    private String latestVersion(String moduleId) {
        return latestVersions.computeIfAbsent(moduleId, k -> Optional.ofNullable(migrations.latestVersion(k)))
                .orElse(null);
    }

    /** Whether {@code current} is missing, unreadable or older than the module's latest shipped version. */
    private boolean behind(String moduleId, String current) {
        String latest = latestVersion(moduleId);
        if (latest == null || current == null) {
            return true;
        }
        try {
            return MigrationVersion.fromVersion(current).compareTo(MigrationVersion.fromVersion(latest)) < 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private java.util.concurrent.locks.ReentrantLock schemaLock(String schema) {
        return schemaLocks[Math.floorMod(schema.hashCode(), schemaLocks.length)];
    }

    @Override
    public void ensureFoundation(String tenantSchema) {
        if (!TenantSchema.isValid(tenantSchema) || foundationEnsured.contains(tenantSchema)) {
            return;
        }
        Backoff backoff = foundationFailures.get(tenantSchema);
        long now = nanoClock.getAsLong();
        if (backoff != null && now - backoff.retryAt() < 0) {
            throw unavailable(backoff.retryAt() - now);
        }
        java.util.concurrent.locks.ReentrantLock lock = schemaLock(tenantSchema);
        boolean locked = false;
        boolean slot = false;
        try {
            locked = lock.tryLock(REQUEST_LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!locked) {
                throw unavailable(java.util.concurrent.TimeUnit.SECONDS.toNanos(5)); // another provisioning runs
            }
            if (foundationEnsured.contains(tenantSchema)) {
                return;
            }
            List<String> ids = foundationModuleIds();
            if (!migrations.schemaExists(tenantSchema)) {
                return; // tenant row without its schema yet (registration in progress): nothing to do, not cached
            }
            Map<String, String> current = migrations.schemaVersions(tenantSchema, ids);
            List<String> modulesBehind = ids.stream().filter(id -> behind(id, current.get(id))).toList();
            if (!modulesBehind.isEmpty()) {
                slot = provisioningSlots.tryAcquire(REQUEST_LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!slot) {
                    throw unavailable(java.util.concurrent.TimeUnit.SECONDS.toNanos(5)); // too many at once
                }
                for (String id : modulesBehind) {
                    ProvisioningResult result = provision(tenantSchema, id);
                    log.info("Foundation module '{}' provisioned for tenant {} (version {} → {})", id,
                            tenantCode(tenantSchema), result.fromVersion(), result.toVersion());
                }
            }
            foundationEnsured.add(tenantSchema);
            foundationFailures.remove(tenantSchema);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        } catch (FoundationUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            Backoff previous = foundationFailures.get(tenantSchema);
            long delay = previous == null ? FIRST_BACKOFF_NANOS : Math.min(MAX_BACKOFF_NANOS, previous.delay() * 2);
            foundationFailures.put(tenantSchema, new Backoff(nanoClock.getAsLong() + delay, delay));
            // our messages carry no schema value nor SQL text; the tenant is named by its code
            log.error("Foundation tables could not be provisioned for tenant {} ({}) — next attempt in {} s",
                    tenantCode(tenantSchema), describe(e), java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(delay));
            throw unavailable(delay);
        } finally {
            if (slot) {
                provisioningSlots.release();
            }
            if (locked) {
                lock.unlock();
            }
        }
    }

    private static FoundationUnavailableException unavailable(long nanos) {
        return new FoundationUnavailableException("Tenant tables could not be provisioned; retry later",
                java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(nanos + 999_999_999L));
    }

    @Override
    public FoundationUpgrade upgradeFoundation(int parallelism) {
        long startedAt = System.nanoTime();
        List<String> ids = foundationModuleIds();
        List<LiveTenant> tenants = rows.liveTenants().stream()
                .filter(t -> TenantSchema.isValid(t.tenantSchema()) && t.schemaPresent())
                .toList();
        List<String> schemas = tenants.stream().map(LiveTenant::tenantSchema).toList();
        // the Flyway history decides (same source as the request path): one chunked read per module
        Map<String, Map<String, String>> versions = new java.util.HashMap<>();
        for (String id : ids) {
            migrations.historyVersions(id, schemas).forEach((schema, version) ->
                    versions.computeIfAbsent(schema, k -> new java.util.HashMap<>()).put(id, version));
        }
        Map<LiveTenant, List<String>> behindTenants = new java.util.LinkedHashMap<>();
        for (LiveTenant tenant : tenants) {
            Map<String, String> current = versions.getOrDefault(tenant.tenantSchema(), Map.of());
            List<String> behindModules = ids.stream().filter(id -> behind(id, current.get(id))).toList();
            if (behindModules.isEmpty()) {
                foundationEnsured.add(tenant.tenantSchema()); // nothing to check again on its first request
            } else {
                behindTenants.put(tenant, behindModules);
            }
        }
        java.util.concurrent.atomic.AtomicInteger provisioned = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        if (!behindTenants.isEmpty()) {
            int threads = Math.max(1, Math.min(parallelism, behindTenants.size()));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads,
                    Thread.ofPlatform().name("foundation-upgrade-", 0).daemon(true).factory());
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            try {
                behindTenants.forEach((tenant, modulesBehind) -> futures.add(pool.submit(() -> {
                    if (upgradeTenant(tenant, modulesBehind)) {
                        provisioned.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                })));
                for (java.util.concurrent.Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        failed.incrementAndGet(); // upgradeTenant never throws; defensive
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // tenants not finished are reported as failed (retried at the next start or on first request)
                failed.set(behindTenants.size() - provisioned.get());
            } finally {
                pool.shutdownNow();
            }
        }
        return new FoundationUpgrade(tenants.size(), provisioned.get(), failed.get(),
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    /** Provisions the tenant's foundation modules found behind, in module order; {@code false} when one failed. */
    private boolean upgradeTenant(LiveTenant tenant, List<String> modulesBehind) {
        String schema = tenant.tenantSchema();
        java.util.concurrent.locks.ReentrantLock lock = schemaLock(schema);
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            provisioningSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lock.unlock();
            return false;
        }
        try {
            boolean ok = true;
            for (String id : modulesBehind) {
                try {
                    provision(schema, id);
                } catch (RuntimeException e) {
                    ok = false;
                    // our messages carry no schema value nor SQL text; the tenant is named by its code
                    log.error("Foundation module '{}' could not be provisioned for tenant {} ({}) — retried at the "
                            + "next start or on its first request", id, tenant.code(), describe(e));
                }
            }
            if (ok) {
                foundationEnsured.add(schema);
            }
            return ok;
        } finally {
            provisioningSlots.release();
            lock.unlock();
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ModuleProvisioningException p ? "failure=" + p.failure() : e.getClass().getSimpleName();
    }

    @Override
    public ProvisioningReport provisionAll(String moduleId, boolean force, ProgressListener progress) {
        ProgressListener listener = progress != null ? progress : ProgressListener.NONE;
        String id = requireModule(moduleId).id();
        List<String> provisioned = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ProvisioningReport.Failure> failures = new ArrayList<>();
        // Story 16.4: tenants already at the latest shipped version are skipped without any Flyway run
        String latest = migrations.hasMigrations(id) ? migrations.latestVersion(id) : null;
        List<LiveTenant> tenants = rows.liveTenants();
        // Story 16.12: core never has a row — its Flyway history tells (one chunked read)
        Map<String, String> versions = latest == null || force ? Map.of()
                : ModuleManifest.CORE_ID.equals(id)
                ? migrations.historyVersions(id, tenants.stream().filter(LiveTenant::schemaPresent)
                        .map(LiveTenant::tenantSchema).toList())
                : rows.provisionedVersions(id);
        int total = tenants.size();
        int done = 0;
        int upToDate = 0;
        listener.progress(0, total);
        for (LiveTenant tenant : tenants) {
            if (done > 0) {
                listener.progress(done, total);
            }
            done++;
            if (!TenantSchema.isValid(tenant.tenantSchema())) {
                // Story 13.9: the platform tenant (schema "public") is not a tenant schema — never provisioned
                skipped.add(tenant.code());
                log.debug("Module '{}' batch: tenant {} skipped (not a tenant schema)", id, tenant.code());
                continue;
            }
            if (!tenant.schemaPresent()) {
                skipped.add(tenant.code());
                log.debug("Module '{}' batch: tenant {} skipped (schema not created yet)", id, tenant.code());
                continue;
            }
            if (sameVersion(latest, versions.get(tenant.tenantSchema()))) {
                upToDate++;
                continue;
            }
            try {
                provision(tenant.tenantSchema(), id);
                provisioned.add(tenant.code());
            } catch (ModuleNotActiveException | TenantNotFoundException e) {
                skipped.add(tenant.code());
                log.debug("Module '{}' batch: tenant {} skipped ({})", id, tenant.code(), e.getMessage());
            } catch (ModuleProvisioningException | IllegalArgumentException e) {
                // our messages carry no schema value nor SQL text
                failures.add(new ProvisioningReport.Failure(tenant.code(),
                        "Création des tables échouée : réessayez plus tard"));
                log.warn("Module '{}' provisioning failed for tenant {} ({})", id, tenant.code(),
                        e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                failures.add(new ProvisioningReport.Failure(tenant.code(),
                        "Création des tables échouée : réessayez plus tard"));
                log.warn("Module '{}' provisioning failed for tenant {} ({})", id, tenant.code(),
                        e.getClass().getSimpleName());
            }
        }
        listener.progress(total, total);
        log.info("Module '{}' batch provisioning{}: {} provisioned, {} skipped, {} already up to date, {} failed", id,
                force ? " (forced)" : "", provisioned.size(), skipped.size(), upToDate, failures.size());
        return new ProvisioningReport(id, provisioned, skipped, failures, upToDate);
    }

    /** Whether a stored version equals the latest shipped one (Flyway comparison: {@code 1.0 == 1}). */
    private static boolean sameVersion(String latest, String stored) {
        if (latest == null || stored == null) {
            return false;
        }
        try {
            return MigrationVersion.fromVersion(latest).equals(MigrationVersion.fromVersion(stored));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Each module in its own transaction ({@code REQUIRES_NEW}, {@link #moduleTransaction}): it runs after the
     * registration commit, where the original transaction's resources may still be bound to the thread — the row
     * writes must commit on their own, and one module's failure must not roll back the others.
     */
    @Override
    public List<ProvisioningResult> provisionActiveModules(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        // Story 16.12: the same per-schema lock and provisioning slots as the request path (the owner's first request
        // may race with this after-commit run)
        java.util.concurrent.locks.ReentrantLock lock = schemaLock(schema);
        lock.lock();
        boolean slot = false;
        try {
            provisioningSlots.acquireUninterruptibly();
            slot = true;
            return provisionActiveModulesLocked(schema);
        } finally {
            if (slot) {
                provisioningSlots.release();
            }
            lock.unlock();
        }
    }

    private List<ProvisioningResult> provisionActiveModulesLocked(String schema) {
        List<ProvisioningResult> results = new ArrayList<>();
        java.util.Set<String> foundationLeft = new java.util.HashSet<>(foundationModuleIds());
        for (ModuleManifest module : dependencyOrder()) {
            String id = module.id();
            if (module.category() == ModuleCategory.PLATFORM || !shipsMigrations(id)) {
                continue;
            }
            try {
                ProvisioningResult result = moduleTransaction.execute(status ->
                        registry.isActive(schema, id) ? provision(schema, id) : null);
                if (result != null) {
                    results.add(result);
                    foundationLeft.remove(id);
                }
            } catch (ModuleNotActiveException | TenantNotFoundException e) {
                log.debug("Module '{}' not provisioned for a new tenant ({})", id, e.getMessage());
            } catch (RuntimeException e) {
                // our messages carry no schema value nor SQL text; the tenant is named by its code
                log.error("Module '{}' provisioning failed for new tenant {} ({}) — the other modules go on; "
                        + "provision it again on demand (provisionAll)", id, tenantCode(schema),
                        e.getClass().getSimpleName());
            }
        }
        if (foundationLeft.isEmpty()) {
            foundationEnsured.add(schema); // every foundation module recorded at its latest version
        }
        return List.copyOf(results);
    }

    /** The tenant code of a live tenant schema, for logs ({@code ?} when unknown). */
    private String tenantCode(String schema) {
        try {
            return rows.tenantCode(schema).orElse("?");
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Installed manifests in table-creation order (Story 16.12: {@link ModuleOrder}, foundation modules first). */
    private List<ModuleManifest> dependencyOrder() {
        List<ModuleManifest> known = order;
        if (known == null) {
            known = ModuleOrder.order(catalog.manifests()); // the catalogue is immutable: computed once
            order = known;
        }
        return known;
    }

    /** The installed manifest; null/blank/unknown ids throw without echoing the raw value. */
    private ModuleManifest requireModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("A module id is required");
        }
        Optional<ModuleManifest> module = catalog.find(moduleId);
        return module.orElseThrow(
                () -> new IllegalArgumentException("Unknown module (length=" + moduleId.length() + ")"));
    }
}
