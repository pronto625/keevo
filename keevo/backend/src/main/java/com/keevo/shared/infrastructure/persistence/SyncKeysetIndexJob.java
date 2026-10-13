package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner.SyncKeysetIndex;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SyncKeysetIndexJob — builds the keyset pull indexes ({@link TenantSchemaProvisioner#SYNC_KEYSET_INDEXES})
 * on EXISTING tenant schemas, in the background (Story 11.5).
 *
 * <p>New schemas get the indexes at provisioning (empty tables). Existing schemas may hold large
 * tables, so the indexes are never built on the request path nor inside
 * {@link TenantSchemaSyncService}: this job runs once after {@link ApplicationReadyEvent} (on its
 * own thread, the boot is not delayed) and again every day, on a single instance (ShedLock lock
 * {@value #LOCK_NAME}).
 *
 * <p>For each valid, existing, non-deleted tenant schema and each index, on an autocommit
 * connection:
 * <ol>
 *   <li>an existing index of that name that is INVALID ({@code pg_index.indisvalid = false}, e.g.
 *       an interrupted concurrent build) or whose definition differs from the expected one is
 *       dropped with {@code DROP INDEX CONCURRENTLY};</li>
 *   <li>{@code SET statement_timeout = '15min'} then {@code CREATE INDEX CONCURRENTLY IF NOT EXISTS}
 *       — no write lock on the table, bounded build time.</li>
 * </ol>
 * A failure is logged with its cause and the job continues with the next index; the next run
 * repairs what is left (an interrupted build leaves an INVALID index, dropped next time).
 */
@Component
public class SyncKeysetIndexJob {

    private static final Logger log = LoggerFactory.getLogger(SyncKeysetIndexJob.class);

    static final String LOCK_NAME = "syncKeysetIndexJob";
    static final String STATEMENT_TIMEOUT_SQL = "SET statement_timeout = '15min'";

    /** Existing index of a given name in a schema: validity and definition. */
    static final String EXISTING_INDEX_SQL =
            "SELECT i.indisvalid, pg_get_indexdef(i.indexrelid) AS def "
            + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_index i ON i.indexrelid = c.oid "
            + "WHERE n.nspname = ? AND c.relname = ?";

    static final String TABLE_EXISTS_SQL =
            "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";

    /** Outcome of one run, for logs and tests. */
    public record Result(int created, int rebuilt, int unchanged, int skipped, int failed) {
        static final Result NONE = new Result(0, 0, 0, 0, 0);

        Result plus(Result o) {
            return new Result(created + o.created, rebuilt + o.rebuilt, unchanged + o.unchanged,
                    skipped + o.skipped, failed + o.failed);
        }
    }

    /** Thrown when the session settings could not be restored: the connection was aborted. */
    private static final class ConnectionAbortedException extends RuntimeException {
        ConnectionAbortedException(Throwable cause) {
            super(cause);
        }
    }

    /** No new schema is started past this budget, well under the lock's {@code lockAtMostFor} (PT6H). */
    static final Duration RUN_BUDGET = Duration.ofHours(5);

    private enum Outcome { CREATED, REBUILT, UNCHANGED, SKIPPED, FAILED }

    private final DataSource dataSource;
    private final LockProvider lockProvider;
    private final boolean enabled;
    /** Package-private for tests. */
    Duration runBudget = RUN_BUDGET;

    public SyncKeysetIndexJob(DataSource dataSource, LockProvider lockProvider,
                              @Value("${keevo.scheduling.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.lockProvider = lockProvider;
        this.enabled = enabled;
    }

    // ── Triggers ─────────────────────────────────────────────────────────────

    /** After the boot (schema sync already done by TenantSchemaMigrationRunner), on its own thread. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        Thread.ofPlatform().name("sync-keyset-index-job").daemon(true).start(this::runAtStartup);
    }

    /** Daily re-run (ShedLock through the proxy): retries failed or interrupted builds. */
    @Scheduled(cron = "${keevo.sync-keyset-index-job.cron:0 40 3 * * *}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT6H", lockAtLeastFor = "PT1M")
    public void daily() {
        safeRunAll();
    }

    /** Startup run under the same ShedLock lock, taken explicitly (no scheduler proxy on this path). */
    void runAtStartup() {
        Optional<SimpleLock> lock;
        try {
            lock = lockProvider.lock(new LockConfiguration(
                    Instant.now(), LOCK_NAME, Duration.ofHours(6), Duration.ofMinutes(1)));
        } catch (RuntimeException e) {
            log.error("SyncKeysetIndexJob: cannot take the lock — startup run skipped", e);
            return;
        }
        if (lock.isEmpty()) {
            log.info("SyncKeysetIndexJob: lock held by another instance — startup run skipped");
            return;
        }
        try {
            safeRunAll();
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                log.warn("SyncKeysetIndexJob: unlock failed — the lock expires by itself", e);
            }
        }
    }

    private void safeRunAll() {
        try {
            runAll();
        } catch (RuntimeException e) {
            log.error("SyncKeysetIndexJob: run failed", e);
        }
    }

    // ── Work ─────────────────────────────────────────────────────────────────

    /** Ensures the indexes on every valid, existing, non-deleted tenant schema. */
    public Result runAll() {
        long startedAt = System.nanoTime();
        Result total = Result.NONE;
        List<String> schemas = loadSchemas();
        for (int i = 0; i < schemas.size(); i++) {
            if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(runBudget) >= 0) {
                log.warn("SyncKeysetIndexJob: run budget {} spent — {} schema(s) left for the next run",
                        runBudget, schemas.size() - i);
                break;
            }
            total = total.plus(ensureIndexes(schemas.get(i)));
        }
        log.info("SyncKeysetIndexJob: done — created={} rebuilt={} unchanged={} skipped={} failed={} elapsedMs={}",
                total.created(), total.rebuilt(), total.unchanged(), total.skipped(), total.failed(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return total;
    }

    /** Ensures every keyset index on one schema; one failure never stops the others. */
    public Result ensureIndexes(String schema) {
        if (!TenantSchema.isValid(schema)) {
            log.warn("SyncKeysetIndexJob: invalid tenant schema name skipped (length={})",
                    schema == null ? "null" : String.valueOf(schema.length()));
            return Result.NONE;
        }
        int created = 0, rebuilt = 0, unchanged = 0, skipped = 0, failed = 0;
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(true); // CONCURRENTLY cannot run inside a transaction block
            boolean aborted = false;
            try {
                for (SyncKeysetIndex index : TenantSchemaProvisioner.SYNC_KEYSET_INDEXES) {
                    Outcome outcome;
                    try {
                        outcome = ensureIndex(conn, schema, index);
                    } catch (ConnectionAbortedException e) {
                        aborted = true; // connection unusable: the remaining indexes wait for the next run
                        failed++;
                        break;
                    }
                    switch (outcome) {
                        case CREATED -> created++;
                        case REBUILT -> rebuilt++;
                        case UNCHANGED -> unchanged++;
                        case SKIPPED -> skipped++;
                        case FAILED -> failed++;
                    }
                }
            } finally {
                if (!aborted) conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException | RuntimeException e) {
            log.error("SyncKeysetIndexJob: schema {} interrupted", schema, e);
            failed++;
        }
        return new Result(created, rebuilt, unchanged, skipped, failed);
    }

    private Outcome ensureIndex(Connection conn, String schema, SyncKeysetIndex index) {
        try (Statement stmt = conn.createStatement()) {
            if (!tableExists(conn, schema, index.table())) {
                log.debug("SyncKeysetIndexJob: {}.{} missing — index {} skipped", schema, index.table(), index.name());
                return Outcome.SKIPPED;
            }
            ExistingIndex existing = existingIndex(conn, schema, index.name());
            boolean rebuild = existing != null;
            if (rebuild) {
                boolean sameDefinition = index.expectedDefinition().equals(unqualified(existing.definition(), schema));
                if (existing.valid() && sameDefinition) return Outcome.UNCHANGED;
                log.warn("SyncKeysetIndexJob: rebuilding {}.{} (valid={}, definition={})",
                        schema, index.name(), existing.valid(), existing.definition());
            }
            stmt.execute(STATEMENT_TIMEOUT_SQL); // bounds the build (lock_timeout would bound only waits)
            try {
                if (rebuild) stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS \"" + schema + "\"." + index.name());
                stmt.execute(index.createConcurrentlySql(schema));
            } finally {
                resetStatementTimeout(conn, stmt);
            }
            log.info("SyncKeysetIndexJob: {} {}.{}", rebuild ? "rebuilt" : "created", schema, index.name());
            return rebuild ? Outcome.REBUILT : Outcome.CREATED;
        } catch (ConnectionAbortedException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            log.error("SyncKeysetIndexJob: index {}.{} failed — retried next run", schema, index.name(), e);
            return Outcome.FAILED;
        }
    }

    /**
     * Restores the default statement timeout; if that fails, the pooled connection is aborted
     * rather than handed back with a 15-minute timeout.
     */
    private static void resetStatementTimeout(Connection conn, Statement stmt) {
        try {
            stmt.execute("RESET statement_timeout");
        } catch (SQLException | RuntimeException e) {
            log.error("SyncKeysetIndexJob: RESET statement_timeout failed — aborting the connection", e);
            try {
                conn.abort(Runnable::run);
            } catch (SQLException | RuntimeException abortFailure) {
                log.warn("SyncKeysetIndexJob: connection abort failed", abortFailure);
            }
            throw new ConnectionAbortedException(e);
        }
    }

    private record ExistingIndex(boolean valid, String definition) {}

    private static ExistingIndex existingIndex(Connection conn, String schema, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(EXISTING_INDEX_SQL)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new ExistingIndex(rs.getBoolean(1), rs.getString(2)) : null;
            }
        }
    }

    private static boolean tableExists(Connection conn, String schema, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(TABLE_EXISTS_SQL)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** {@code pg_get_indexdef} qualifies the table unless its schema is on the search_path. */
    private static String unqualified(String definition, String schema) {
        return definition.replace(" ON " + schema + ".", " ON ").replace(" ON \"" + schema + "\".", " ON ");
    }

    private List<String> loadSchemas() {
        List<String> schemas = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(TenantSchemaMigrationRunner.LOAD_TENANTS_SQL)) {
            while (rs.next()) {
                String schema = rs.getString("schema_name");
                if (rs.getBoolean("schema_present") && TenantSchema.isValid(schema)) schemas.add(schema);
            }
        } catch (SQLException e) {
            log.error("SyncKeysetIndexJob: cannot list tenant schemas", e);
        }
        return schemas;
    }
}
