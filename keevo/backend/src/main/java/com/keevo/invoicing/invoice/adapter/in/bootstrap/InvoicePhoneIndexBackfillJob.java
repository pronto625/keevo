package com.keevo.invoicing.invoice.adapter.in.bootstrap;

import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.capability.portal.InvoicePhoneIndex;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleProvisioner;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.7 — one-shot boot data migration: every invoice issued before the client portal is indexed by its client's
 * phone ({@link InvoicePhoneIndex}), so a client finds his older invoices too. Same template as
 * {@code ClientPhoneBackfillJob}: {@link ApplicationReadyEvent}, own daemon thread, one instance (ShedLock
 * {@value #LOCK_NAME}), gated by {@code keevo.scheduling.enabled}, done-marker {@value #MARKER} in
 * {@code public.data_migrations}.
 *
 * <p>Only the tenants whose schema holds an {@code invoices} table (one catalog join, not one probe per tenant); for
 * each, the invoicing tables are brought to their latest version, then keyset pages of {@value #BATCH} issued / paid /
 * cancelled invoices with a phone, one batch insert per page (the phone is normalized in Java, exactly as at issue).
 * Idempotent (the index upserts): a rerun changes nothing. A failed tenant is logged and skipped and leaves no
 * marker (next boot retries); a connection failure stops the run.
 */
@Component
public class InvoicePhoneIndexBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(InvoicePhoneIndexBackfillJob.class);

    static final String LOCK_NAME = "invoicePhoneIndexBackfillJob";
    static final String MARKER = "client-portal-invoice-phone-index-backfill";
    static final int BATCH = 500;
    /** Below every id in PostgreSQL uuid order (unsigned bytes). */
    private static final UUID LOWEST = new UUID(0L, 0L);

    static final String MARKER_DONE_SQL = "SELECT count(*) FROM public.data_migrations WHERE name = ?";
    static final String MARKER_SQL =
            "INSERT INTO public.data_migrations (name, done_at) VALUES (?, now()) ON CONFLICT (name) DO NOTHING";
    /** Live tenants whose schema has an invoices table (the invoicing module was used there). */
    static final String FETCH_SCHEMAS =
            "SELECT t.schema_name FROM public.tenants t JOIN pg_catalog.pg_tables p ON p.schemaname = t.schema_name "
            + "AND p.tablename = 'invoices' WHERE t.status <> 'DELETED' ORDER BY t.schema_name";
    static final String PAGE_SQL =
            "SELECT id, client_phone, issued_at FROM \"%s\".invoices WHERE kind = 'INVOICE' "
            + "AND status IN ('ISSUED', 'PAID', 'CANCELLED') AND deleted_at IS NULL AND superseded_at IS NULL "
            + "AND client_phone IS NOT NULL "
            + "AND issued_at IS NOT NULL AND id > ? ORDER BY id LIMIT " + BATCH;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LockProvider lockProvider;
    private final InvoicePhoneIndex index;
    private final ObjectProvider<ModuleProvisioner> provisioner;
    private final boolean enabled;

    public InvoicePhoneIndexBackfillJob(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                        LockProvider lockProvider, InvoicePhoneIndex index,
                                        ObjectProvider<ModuleProvisioner> provisioner,
                                        @Value("${keevo.scheduling.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.lockProvider = lockProvider;
        this.index = index;
        this.provisioner = provisioner;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        Thread.ofPlatform().name("invoice-phone-index-backfill").daemon(true).start(this::runAtStartup);
    }

    void runAtStartup() {
        Optional<SimpleLock> lock;
        try {
            lock = lockProvider.lock(new LockConfiguration(
                    Instant.now(), LOCK_NAME, Duration.ofMinutes(30), Duration.ofSeconds(10)));
        } catch (RuntimeException e) {
            log.error("InvoicePhoneIndexBackfillJob: cannot take the lock — run skipped", e);
            return;
        }
        if (lock.isEmpty()) {
            log.info("InvoicePhoneIndexBackfillJob: lock held by another instance — run skipped");
            return;
        }
        try {
            backfillAll();
        } catch (RuntimeException e) {
            log.error("InvoicePhoneIndexBackfillJob: run failed", e);
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                log.warn("InvoicePhoneIndexBackfillJob: unlock failed — the lock expires by itself", e);
            }
        }
    }

    /** @return index rows written over all tenants (0 when already done) */
    public int backfillAll() {
        Integer marked = jdbc.queryForObject(MARKER_DONE_SQL, Integer.class, MARKER);
        if (marked != null && marked > 0) {
            log.debug("InvoicePhoneIndexBackfillJob: already done ({}) — nothing to do", MARKER);
            return 0;
        }
        int written = 0;
        int failed = 0;
        for (String schema : jdbc.queryForList(FETCH_SCHEMAS, String.class)) {
            if (!TenantSchema.isValid(schema)) continue;
            try {
                written += backfillTenant(schema);
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e;
            } catch (RuntimeException e) {
                failed++;
                log.warn("InvoicePhoneIndexBackfillJob: tenant {} skipped ({}: {})", schema,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("InvoicePhoneIndexBackfillJob: {} invoice(s) indexed, {} tenant(s) failed", written, failed);
        if (failed == 0) {
            jdbc.update(MARKER_SQL, MARKER);
        }
        return written;
    }

    /** Keyset pages of {@value #BATCH} invoices, each indexed in one batch inside its own short transaction. */
    int backfillTenant(String schema) {
        String safe = TenantSchema.validate(schema);
        ModuleProvisioner p = provisioner.getIfAvailable();
        if (p != null) {
            p.ensureProvisioned(safe, InvoicingModule.ID);
        }
        String page = String.format(PAGE_SQL, safe);
        int written = 0;
        UUID after = LOWEST;
        while (true) {
            final UUID from = after;
            List<InvoicePhoneIndex.Entry> entries = new ArrayList<>();
            List<UUID> read = new ArrayList<>();
            Integer count = tx.execute(status -> {
                jdbc.query(page, rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    read.add(id);
                    Timestamp issuedAt = rs.getTimestamp("issued_at");
                    entries.add(new InvoicePhoneIndex.Entry(id, rs.getString("client_phone"), issuedAt.toInstant()));
                }, from);
                return entries.isEmpty() ? 0 : index.indexAll(safe, entries);
            });
            written += count == null ? 0 : count;
            if (read.size() < BATCH) break;
            after = read.get(read.size() - 1);
        }
        return written;
    }
}
