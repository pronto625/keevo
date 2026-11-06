package com.keevo.catalog.product.adapter.in.bootstrap;

import com.keevo.catalog.product.domain.service.ProductKeys;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ProductKeyBackfillJob — Story 20.1 one-shot boot data migration: every existing product gets its
 * {@code product_key} (Bref rules, {@link ProductKeys}) and {@code price_manual = true} (every Keevo price so far was
 * typed by the owner). Module scripts may not update data (db/modules/README), hence a boot job; same template as
 * {@code PlanMigrationJob}: {@link ApplicationReadyEvent}, own daemon thread, one instance (ShedLock
 * {@value #LOCK_NAME}), gated by {@code keevo.scheduling.enabled}, done-marker {@value #MARKER} in
 * {@code public.data_migrations}.
 *
 * <p>Per live tenant, keyset pages of {@value #BATCH} rows {@code WHERE product_key IS NULL ORDER BY id} (the key is
 * computed in Java — Unicode NFD is not portable SQL), each page in its own short transaction ({@code SET LOCAL
 * lock_timeout = '10s'}: never wait long behind the running application) with one JDBC batch UPDATE (no per-row
 * query) that sets the key and {@code price_manual = true} on those pre-existing rows only. A product created after the
 * deploy already carries its key, so its {@code price_manual} is never touched. {@code updated_at} is not bumped:
 * devices need no re-pull. Idempotent: a rerun finds nothing to change. A failed tenant is logged and skipped and leaves no marker (next boot
 * retries); a connection failure stops the run.
 */
@Component
public class ProductKeyBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(ProductKeyBackfillJob.class);

    static final String LOCK_NAME = "productKeyBackfillJob";
    static final String MARKER = "catalog-product-key-backfill";
    static final int BATCH = 500;
    /** Below every id in PostgreSQL uuid order (unsigned bytes). */
    private static final UUID LOWEST = new UUID(0L, 0L);

    static final String MARKER_DONE_SQL = "SELECT count(*) FROM public.data_migrations WHERE name = ?";
    static final String MARKER_SQL =
            "INSERT INTO public.data_migrations (name, done_at) VALUES (?, now()) ON CONFLICT (name) DO NOTHING";
    static final String FETCH_TENANTS =
            "SELECT id, schema_name FROM public.tenants WHERE status <> 'DELETED' "
            + "AND id <> '00000000-0000-0000-0000-000000000000'::uuid";
    static final String PAGE_SQL =
            "SELECT id, name, barcode FROM \"%s\".products WHERE product_key IS NULL AND id > ? ORDER BY id LIMIT "
            + BATCH;
    static final String KEY_SQL =
            "UPDATE \"%s\".products SET product_key = ?, price_manual = true WHERE id = ? AND product_key IS NULL";
    static final String LOCK_TIMEOUT_SQL = "SET LOCAL lock_timeout = '10s'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LockProvider lockProvider;
    private final boolean enabled;

    public ProductKeyBackfillJob(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                 LockProvider lockProvider,
                                 @Value("${keevo.scheduling.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.lockProvider = lockProvider;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        Thread.ofPlatform().name("product-key-backfill-job").daemon(true).start(this::runAtStartup);
    }

    void runAtStartup() {
        Optional<SimpleLock> lock;
        try {
            lock = lockProvider.lock(new LockConfiguration(
                    Instant.now(), LOCK_NAME, Duration.ofMinutes(30), Duration.ofSeconds(10)));
        } catch (RuntimeException e) {
            log.error("ProductKeyBackfillJob: cannot take the lock — run skipped", e);
            return;
        }
        if (lock.isEmpty()) {
            log.info("ProductKeyBackfillJob: lock held by another instance — run skipped");
            return;
        }
        try {
            backfillAll();
        } catch (RuntimeException e) {
            log.error("ProductKeyBackfillJob: run failed", e);
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                log.warn("ProductKeyBackfillJob: unlock failed — the lock expires by itself", e);
            }
        }
    }

    /** @return number of product rows changed (keys set + manual prices set) over all tenants */
    public int backfillAll() {
        Integer marked = jdbc.queryForObject(MARKER_DONE_SQL, Integer.class, MARKER);
        if (marked != null && marked > 0) {
            log.debug("ProductKeyBackfillJob: already done ({}) — nothing to do", MARKER);
            return 0;
        }
        int changed = 0;
        int failed = 0;
        for (Map<String, Object> tenant : jdbc.queryForList(FETCH_TENANTS)) {
            String schema = (String) tenant.get("schema_name");
            if (!TenantSchema.isValid(schema)) continue;
            try {
                changed += backfillTenant(schema);
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e;
            } catch (RuntimeException e) {
                failed++;
                log.warn("ProductKeyBackfillJob: tenant {} skipped ({}: {})", tenant.get("id"),
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("ProductKeyBackfillJob: {} product row(s) updated, {} tenant(s) failed", changed, failed);
        if (failed == 0) {
            jdbc.update(MARKER_SQL, MARKER);
        }
        return changed;
    }

    /** Keyset pages of missing keys, one short transaction per page (committed page by page). */
    int backfillTenant(String schema) {
        String safe = TenantSchema.validate(schema);
        String page = String.format(PAGE_SQL, safe);
        String update = String.format(KEY_SQL, safe);
        int changed = 0;
        UUID after = LOWEST;
        while (true) {
            final UUID from = after;
            PageResult result = tx.execute(status -> keyPage(page, update, from));
            if (result == null || result.last() == null) break;
            changed += result.changed();
            after = result.last();
            if (result.read() < BATCH) break;
        }
        return changed;
    }

    private record PageResult(int read, UUID last, int changed) {}

    /** In the page's transaction: read up to {@value #BATCH} rows after {@code after}, key them in one batch. */
    private PageResult keyPage(String page, String update, UUID after) {
        jdbc.execute(LOCK_TIMEOUT_SQL);
        List<Object[]> keys = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        jdbc.query(page, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            ids.add(id);
            String key = ProductKeys.key(rs.getString("name"), rs.getString("barcode"));
            if (key != null) keys.add(new Object[]{key, id});
        }, after);
        if (ids.isEmpty()) return new PageResult(0, null, 0);
        int changed = 0;
        if (!keys.isEmpty()) {
            for (int count : jdbc.batchUpdate(update, keys)) {
                changed += Math.max(count, 0);
            }
        }
        return new PageResult(ids.size(), ids.get(ids.size() - 1), changed);
    }
}
