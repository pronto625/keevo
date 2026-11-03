package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanSettings;
import com.keevo.shared.capability.entitlement.UnknownPlanException;
import com.keevo.shared.module.application.port.PlanCatalogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Story 19.7a — JDBC adapter of the plan catalogue ({@link PlanCatalog}, read) and of its write side
 * ({@link PlanCatalogStore}) on {@code public.plans} + {@code public.plan_settings}.
 *
 * <p><b>Cache</b> (FUS-NFR11): the catalogue is a handful of rows, read whole (two queries) into an immutable
 * snapshot; every read is a lookup in that snapshot. The snapshot is marked stale on every write through this adapter
 * (at once and after the caller's commit — a load overlapping the write is installed already stale), reloaded after
 * {@link #TTL} (writes made by another instance), and reloaded on a miss at most once per
 * {@link #MISS_RELOAD_AFTER} (a code just created elsewhere). A stale snapshot keeps being served while one thread
 * reloads; only a reader with no snapshot at all waits. The load runs on a
 * dedicated connection, outside the caller's transaction, with a short timeout: a fault there never aborts the
 * caller's PostgreSQL transaction.
 *
 * <p><b>Fallback</b>: a load that fails keeps the last snapshot read (WARN); without any, the V42 seed
 * ({@link PlanCatalog#SEED}, ERROR logged), retried after {@link #FALLBACK_RETRY}. Never an exception.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcPlanCatalog implements PlanCatalog, PlanCatalogStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlanCatalog.class);

    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration MISS_RELOAD_AFTER = Duration.ofSeconds(1);
    static final Duration FALLBACK_RETRY = Duration.ofSeconds(5);
    static final int QUERY_TIMEOUT_SECONDS = 3;

    static final String PLANS_SQL = """
            SELECT code, label, description, sort_order, visible, kind, archived_at
            FROM public.plans ORDER BY sort_order, code""";

    static final String SETTINGS_SQL =
            "SELECT free_plan_code, trial_plan_code, trial_months FROM public.plan_settings WHERE id = 1";

    static final String UPSERT_PLAN_SQL = """
            INSERT INTO public.plans (code, label, description, sort_order, visible, kind, archived_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label, description = EXCLUDED.description,
                sort_order = EXCLUDED.sort_order, visible = EXCLUDED.visible, kind = EXCLUDED.kind,
                archived_at = EXCLUDED.archived_at, updated_at = now()""";

    static final String UPSERT_SETTINGS_SQL = """
            INSERT INTO public.plan_settings (id, free_plan_code, trial_plan_code, trial_months)
            VALUES (1, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET free_plan_code = EXCLUDED.free_plan_code,
                trial_plan_code = EXCLUDED.trial_plan_code, trial_months = EXCLUDED.trial_months,
                updated_at = now()""";

    /**
     * A loaded catalogue, the instant (nanos) until which it is fresh, and whether it is the built-in fallback. A
     * stale snapshot is still served while it is reloaded, and kept when the reload fails.
     */
    private record Snapshot(PlanCatalog catalog, long freshUntil, boolean fallback) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final LongSupplier nanoClock;
    private final ReentrantLock reloadLock = new ReentrantLock();
    /** Bumped by every invalidation: a load started before it is installed already stale. */
    private final AtomicLong generation = new AtomicLong();
    private volatile Snapshot snapshot;
    /** Last load attempt (nanos), success or not: rate-limits the reloads on a miss. */
    private volatile long lastAttempt;

    @Autowired
    public JdbcPlanCatalog(DataSource dataSource) {
        this(dataSource, System::nanoTime);
    }

    /** Test seam: the monotonic clock of the cache. */
    JdbcPlanCatalog(DataSource dataSource, LongSupplier nanoClock) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.nanoClock = nanoClock;
        this.lastAttempt = nanoClock.getAsLong() - MISS_RELOAD_AFTER.toNanos();
    }

    // ── PlanCatalog ───────────────────────────────────────────────────────────

    @Override
    public Optional<PlanDefinition> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        Snapshot current = current();
        Optional<PlanDefinition> found = current.catalog().find(code);
        if (found.isEmpty() && nanoClock.getAsLong() - lastAttempt >= MISS_RELOAD_AFTER.toNanos()
                && PlanType.CODE.matcher(code).matches()) {
            found = reload(current).catalog().find(code); // a plan just created by another instance
        }
        return found;
    }

    @Override
    public List<PlanDefinition> all() {
        return current().catalog().all();
    }

    @Override
    public PlanSettings settings() {
        return current().catalog().settings();
    }

    // ── PlanCatalogStore ──────────────────────────────────────────────────────

    @Override
    public void save(PlanDefinition plan) {
        jdbc.update(UPSERT_PLAN_SQL, plan.code().code(), plan.label(), plan.description(), plan.sortOrder(),
                plan.visible(), plan.kind().name(),
                plan.archivedAt() != null ? Timestamp.from(plan.archivedAt()) : null);
        invalidateNowAndAfterCommit();
    }

    /**
     * @throws IllegalArgumentException the free plan is not a live plan of kind FREE, or the trial plan not a live
     *                                  plan of kind TRIAL (a paid « free » target would make the expiry job downgrade
     *                                  onto a plan that expires again)
     */
    @Override
    public void saveSettings(PlanSettings settings) {
        requireLive(settings.freePlan(), PlanKind.FREE, "free");
        requireLive(settings.trialPlan(), PlanKind.TRIAL, "trial");
        jdbc.update(UPSERT_SETTINGS_SQL, settings.freePlan().code(), settings.trialPlan().code(),
                settings.trialMonths());
        invalidateNowAndAfterCommit();
    }

    private void requireLive(PlanType code, PlanKind kind, String role) {
        PlanDefinition plan = find(code).orElseThrow(UnknownPlanException::new);
        if (plan.kind() != kind || plan.archived()) {
            throw new IllegalArgumentException("The " + role + " plan must be a live plan of kind " + kind);
        }
    }

    /** Marks the cached catalogue stale (kept, so a failing reload still serves it); the next read reloads. */
    @Override
    public void invalidate() {
        generation.incrementAndGet();
        Snapshot current = snapshot;
        if (current != null) {
            snapshot = new Snapshot(current.catalog(), nanoClock.getAsLong(), current.fallback());
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private void invalidateNowAndAfterCommit() {
        invalidate();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    invalidate(); // a read between the write and the commit cached the old rows
                }
            });
        }
    }

    private Snapshot current() {
        Snapshot current = snapshot;
        if (current != null && nanoClock.getAsLong() - current.freshUntil() < 0) {
            return current;
        }
        return reload(current);
    }

    /**
     * One load at a time. With a snapshot at hand (fresh or stale) a reader never waits for another thread's load: it
     * gets that snapshot. Only a reader without any snapshot blocks.
     */
    private Snapshot reload(Snapshot seen) {
        if (seen == null) {
            reloadLock.lock();
        } else if (!reloadLock.tryLock()) {
            return seen;
        }
        try {
            Snapshot current = snapshot;
            if (current != null && current != seen) {
                return current; // reloaded by another thread meanwhile
            }
            return load(current);
        } finally {
            reloadLock.unlock();
        }
    }

    /** Under the reload lock: loads and installs the catalogue, or keeps the last one, or the seed. */
    private Snapshot load(Snapshot current) {
        long started = generation.get();
        long now = nanoClock.getAsLong();
        lastAttempt = now;
        Snapshot next;
        try {
            PlanCatalog loaded = load();
            // invalidated while loading: these rows may predate the write — served, but reloaded at once
            long freshUntil = generation.get() == started ? now + TTL.toNanos() : now;
            next = new Snapshot(loaded, freshUntil, false);
        } catch (SQLException | RuntimeException e) {
            if (current != null && !current.fallback()) {
                log.warn("Plan catalogue: reload failed ({}: {}) — last catalogue read kept",
                        e.getClass().getSimpleName(), e.getMessage());
                next = new Snapshot(current.catalog(), now + FALLBACK_RETRY.toNanos(), false);
            } else {
                log.error("Plan catalogue: unreadable ({}: {}) — built-in seed catalogue used",
                        e.getClass().getSimpleName(), e.getMessage());
                next = new Snapshot(PlanCatalog.SEED, now + FALLBACK_RETRY.toNanos(), true);
            }
        }
        snapshot = next;
        return next;
    }

    private PlanCatalog load() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            List<PlanDefinition> plans = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(PLANS_SQL)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        OffsetDateTime archived = rs.getObject("archived_at", OffsetDateTime.class);
                        plans.add(new PlanDefinition(PlanType.of(rs.getString("code")), rs.getString("label"),
                                rs.getString("description"), rs.getInt("sort_order"), rs.getBoolean("visible"),
                                PlanKind.valueOf(rs.getString("kind")),
                                archived != null ? archived.toInstant() : null));
                    }
                }
            }
            PlanSettings settings;
            try (PreparedStatement ps = connection.prepareStatement(SETTINGS_SQL)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("public.plan_settings has no row");
                    }
                    settings = new PlanSettings(PlanType.of(rs.getString("free_plan_code")),
                            PlanType.of(rs.getString("trial_plan_code")), rs.getInt("trial_months"));
                }
            }
            return PlanCatalog.of(plans, settings);
        }
    }
}
