package com.keevo.crm.contact.adapter.in.bootstrap;

import com.keevo.shared.domain.PhoneNumbers;
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
 * ClientPhoneBackfillJob — Story 20.2 one-shot boot data migration: every existing client gets its
 * {@code phone_normalized} (E.164, {@link PhoneNumbers#toE164}) for the same-store duplicate warning. Module scripts
 * may not update data (db/modules/README), hence a boot job; same template as {@code ProductKeyBackfillJob}:
 * {@link ApplicationReadyEvent}, own daemon thread, one instance (ShedLock {@value #LOCK_NAME}), gated by
 * {@code keevo.scheduling.enabled}, done-marker {@value #MARKER} in {@code public.data_migrations}.
 *
 * <p>Per live tenant: one set-based statement for the rows whose stored phone is already E.164 (every client written
 * by Keevo: the domain always stored E.164, for which the Java normalization is the identity) —
 * {@code phone_normalized = phone}; then keyset pages of {@value #BATCH} rows in Java for whatever is left (a drifted
 * phone with separators or without {@code +}), one JDBC batch per page; a phone that cannot be normalized stays NULL
 * (never a duplicate). {@code updated_at} is not bumped (devices need no re-pull), {@code store_id} is never touched.
 * Each step in its own short transaction ({@code SET LOCAL lock_timeout = '10s'}). Idempotent: a rerun finds nothing
 * to change. A failed tenant is logged and skipped and leaves no marker (next boot retries); a connection failure
 * stops the run.
 */
@Component
public class ClientPhoneBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(ClientPhoneBackfillJob.class);

    static final String LOCK_NAME = "clientPhoneBackfillJob";
    static final String MARKER = "crm-client-phone-normalized-backfill";
    static final int BATCH = 500;
    /** Below every id in PostgreSQL uuid order (unsigned bytes). */
    private static final UUID LOWEST = new UUID(0L, 0L);

    static final String MARKER_DONE_SQL = "SELECT count(*) FROM public.data_migrations WHERE name = ?";
    static final String MARKER_SQL =
            "INSERT INTO public.data_migrations (name, done_at) VALUES (?, now()) ON CONFLICT (name) DO NOTHING";
    static final String FETCH_TENANTS =
            "SELECT id, schema_name FROM public.tenants WHERE status <> 'DELETED' "
            + "AND id <> '00000000-0000-0000-0000-000000000000'::uuid";
    /** Same pattern as {@code PhoneNumbers} E.164: for such a phone the Java normalization returns it unchanged. */
    static final String E164_SQL =
            "UPDATE \"%s\".clients SET phone_normalized = phone "
            + "WHERE phone_normalized IS NULL AND phone ~ '" + PhoneNumbers.E164_REGEX + "'";
    static final String PAGE_SQL =
            "SELECT id, phone FROM \"%s\".clients WHERE phone_normalized IS NULL AND id > ? ORDER BY id LIMIT " + BATCH;
    static final String UPDATE_SQL =
            "UPDATE \"%s\".clients SET phone_normalized = ? WHERE id = ? AND phone_normalized IS NULL";
    static final String LOCK_TIMEOUT_SQL = "SET LOCAL lock_timeout = '10s'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LockProvider lockProvider;
    private final boolean enabled;

    public ClientPhoneBackfillJob(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
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
        Thread.ofPlatform().name("client-phone-backfill-job").daemon(true).start(this::runAtStartup);
    }

    void runAtStartup() {
        Optional<SimpleLock> lock;
        try {
            lock = lockProvider.lock(new LockConfiguration(
                    Instant.now(), LOCK_NAME, Duration.ofMinutes(30), Duration.ofSeconds(10)));
        } catch (RuntimeException e) {
            log.error("ClientPhoneBackfillJob: cannot take the lock — run skipped", e);
            return;
        }
        if (lock.isEmpty()) {
            log.info("ClientPhoneBackfillJob: lock held by another instance — run skipped");
            return;
        }
        try {
            backfillAll();
        } catch (RuntimeException e) {
            log.error("ClientPhoneBackfillJob: run failed", e);
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                log.warn("ClientPhoneBackfillJob: unlock failed — the lock expires by itself", e);
            }
        }
    }

    /** @return number of client rows filled over all tenants */
    public int backfillAll() {
        Integer marked = jdbc.queryForObject(MARKER_DONE_SQL, Integer.class, MARKER);
        if (marked != null && marked > 0) {
            log.debug("ClientPhoneBackfillJob: already done ({}) — nothing to do", MARKER);
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
                log.warn("ClientPhoneBackfillJob: tenant {} skipped ({}: {})", tenant.get("id"),
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("ClientPhoneBackfillJob: {} client row(s) updated, {} tenant(s) failed", changed, failed);
        if (failed == 0) {
            jdbc.update(MARKER_SQL, MARKER);
        }
        return changed;
    }

    /** One set-based statement for E.164 phones, then keyset pages for the rest; each in a short transaction. */
    int backfillTenant(String schema) {
        String safe = TenantSchema.validate(schema);
        String e164 = String.format(E164_SQL, safe);
        Integer setBased = tx.execute(status -> {
            jdbc.execute(LOCK_TIMEOUT_SQL);
            return jdbc.update(e164);
        });
        int changed = setBased == null ? 0 : setBased;
        String page = String.format(PAGE_SQL, safe);
        String update = String.format(UPDATE_SQL, safe);
        UUID after = LOWEST;
        while (true) {
            final UUID from = after;
            PageResult result = tx.execute(status -> normalizePage(page, update, from));
            if (result == null || result.last() == null) break;
            changed += result.changed();
            after = result.last();
            if (result.read() < BATCH) break;
        }
        return changed;
    }

    private record PageResult(int read, UUID last, int changed) {}

    /** In the page's transaction: read up to {@value #BATCH} rows after {@code after}, fill them in one batch. */
    private PageResult normalizePage(String page, String update, UUID after) {
        jdbc.execute(LOCK_TIMEOUT_SQL);
        List<Object[]> values = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        jdbc.query(page, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            ids.add(id);
            String normalized = PhoneNumbers.toE164(rs.getString("phone"), PhoneNumbers.CAMEROON);
            if (normalized != null) values.add(new Object[]{normalized, id});
        }, after);
        if (ids.isEmpty()) return new PageResult(0, null, 0);
        int changed = 0;
        if (!values.isEmpty()) {
            for (int count : jdbc.batchUpdate(update, values)) {
                changed += Math.max(count, 0);
            }
        }
        return new PageResult(ids.size(), ids.get(ids.size() - 1), changed);
    }
}
