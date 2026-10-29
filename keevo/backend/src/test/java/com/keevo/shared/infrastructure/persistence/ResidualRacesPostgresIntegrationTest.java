package com.keevo.shared.infrastructure.persistence;

import com.keevo.commerce.api.ClosureWindow;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase.CorrectSaleCommand;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.api.ReportChannel;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductCommand;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductUseCase;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase.WeeklyReportCommand;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.DeterministicIds;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.in.CreateStoreCommand;
import com.keevo.store.store.domain.port.in.CreateStoreUseCase;
import com.keevo.sync.sync.application.handler.CreateStoreSyncHandler;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story v1s-13-13 — residual check-then-act races, proven on a real PostgreSQL 16 with the
 * production beans.
 *
 * <p>Race pattern: thread A opens a transaction, takes the SAME advisory lock the production
 * code takes, inserts the competing row and holds; thread B calls the production bean. B must
 * block on the lock (it is waiting on an advisory lock and not finished after ~700 ms); once A
 * commits, B sees A's row. Were the count/guard read before the lock, B would have saved a
 * second row and the final count assertions would fail.
 *
 * <p>Every test provisions its own tenant and asserts only on its rows (shared database).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Residual check-then-act races on PostgreSQL (v1s-13-13)")
class ResidualRacesPostgresIntegrationTest extends FullContextIntegrationTest {

    private static final int TIMEOUT_SECONDS = 30;
    private static final long BLOCKED_MILLIS = 700;
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final String JUSTIFICATION = "Erreur de saisie au comptoir, client remboursé";

    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionLockPort transactionLock;
    @Autowired CreateStoreUseCase createStore;
    @Autowired CreateStoreSyncHandler createStoreSyncHandler;
    @Autowired CreateEmployeeUseCase createEmployee;
    @Autowired ReactivateEmployeeUseCase reactivateEmployee;
    @Autowired QuickAddProductUseCase quickAddProduct;
    @Autowired GenerateWeeklyReportUseCase weeklyReportGenerator;
    @Autowired com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository reportRepository;
    @Autowired CancelPendingSaleUseCase cancelSale;
    @Autowired CorrectSaleUseCase correctSale;
    @Autowired SaleRepository saleRepository;

    @PersistenceContext EntityManager em;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── Seeding ───────────────────────────────────────────────────────────────

    /** A fresh tenant on the FREE plan (1 store, 3 employees) with its seeded store deactivated. */
    private String freeTenantWithoutActiveStore() {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE'");
        jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = FALSE");
        return schema;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String domainCode(Throwable t) {
        assertThat(t).isInstanceOf(DomainException.class);
        return ((DomainException) t).getDomainCode();
    }

    private static void authenticateOwner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    // ── Race harness ──────────────────────────────────────────────────────────

    /**
     * Runs {@code competitor} under the lock {@code key} in thread A (holding until released),
     * then {@code contender} in thread B; asserts B blocks on the advisory lock, releases A and
     * returns B's outcome (value or ExecutionException).
     */
    private <T> Future<T> race(String schema, String key, String competingInsert, Object[] insertArgs,
                               Callable<T> contender, ExecutorService pool) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> {
            TenantContext.setCurrentTenant(schema);
            try {
                tx.executeWithoutResult(s -> {
                    transactionLock.lock(key);
                    var q = em.createNativeQuery(competingInsert);
                    for (int i = 0; i < insertArgs.length; i++) q.setParameter(i + 1, insertArgs[i]);
                    assertThat(q.executeUpdate()).isEqualTo(1);
                    locked.countDown();
                    try {
                        assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            } finally {
                TenantContext.clear();
            }
        });
        try {
            assertThat(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("A holds the lock").isTrue();
            Future<T> contended = pool.submit(() -> {
                TenantContext.setCurrentTenant(schema);
                try {
                    return contender.call();
                } finally {
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                }
            });

            assertThat(waitsOnAdvisoryLock(key)).as("B waits on the advisory lock").isTrue();
            assertThatThrownBy(() -> contended.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS))
                    .as("B must not finish while A holds the lock")
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                contended.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // B's outcome is asserted by the caller.
            }
            return contended;
        } finally {
            release.countDown();
        }
    }

    /**
     * Whether some session waits (not granted) on the advisory lock of exactly {@code key}
     * (shared database: other tests and TenantSchemaSyncService take advisory locks too).
     * A single-bigint advisory key is stored as classid = high 32 bits, objid = low 32 bits,
     * objsubid = 1.
     */
    private boolean waitsOnAdvisoryLock(String key) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted "
                            + "AND database = (SELECT oid FROM pg_database WHERE datname = current_database()) "
                            + "AND objsubid = 1 "
                            + "AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0))",
                    Boolean.class, key))) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static <T> T outcome(Future<T> f) throws Exception {
        return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Throwable failure(Future<?> f) {
        try {
            f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected B to fail");
    }

    private static String insertStore(String schema) {
        return "INSERT INTO \"" + schema + "\".stores (id, name) VALUES (?1, 'Boutique A')";
    }

    // ── Item 1: store plan limit ──────────────────────────────────────────────

    @Test
    @DisplayName("store limit (REST): B blocks on store-limit lock, then PLAN_LIMIT_EXCEEDED; one store")
    void storeLimit_rest_concurrentCreate_exactlyOne() throws Exception {
        String schema = freeTenantWithoutActiveStore();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> b = race(schema, "store-limit:" + schema, insertStore(schema),
                    new Object[]{UUID.randomUUID()},
                    () -> createStore.execute(new CreateStoreCommand("Boutique B", StoreType.STORE, null, null,
                            UUID.randomUUID())),
                    pool);

            assertThat(domainCode(failure(b))).isEqualTo("PLAN_LIMIT_EXCEEDED");
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".stores WHERE is_active")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("store limit (CREATE_STORE sync in a transaction): B blocks, then REJECTED PLAN_LIMIT_EXCEEDED")
    void storeLimit_sync_concurrentCreate_rejected() throws Exception {
        String schema = freeTenantWithoutActiveStore();
        UUID storeB = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SyncOperationResult> b = race(schema, "store-limit:" + schema, insertStore(schema),
                    new Object[]{UUID.randomUUID()},
                    () -> {
                        authenticateOwner();
                        return tx.execute(s -> createStoreSyncHandler.handle(syncCreateStore(storeB),
                                UUID.randomUUID(), schema));
                    },
                    pool);

            SyncOperationResult result = outcome(b);
            assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
            assertThat(result.reason()).isEqualTo("PLAN_LIMIT_EXCEEDED");
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".stores WHERE is_active")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".stores WHERE id = ?", storeB)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CREATE_STORE replay of the same id: lock before the idempotency read → APPLIED, one store")
    void storeLimit_syncReplaySameId_applied() throws Exception {
        String schema = freeTenantWithoutActiveStore();
        UUID storeId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SyncOperationResult> b = race(schema, "store-limit:" + schema, insertStore(schema),
                    new Object[]{storeId},
                    () -> {
                        authenticateOwner();
                        return tx.execute(s -> createStoreSyncHandler.handle(syncCreateStore(storeId),
                                UUID.randomUUID(), schema));
                    },
                    pool);

            assertThat(outcome(b).status()).isEqualTo(SyncOperationStatus.APPLIED);
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".stores WHERE is_active")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static SyncOperation syncCreateStore(UUID storeId) {
        return new SyncOperation("op-" + UUID.randomUUID(), "CREATE_STORE", storeId.toString(),
                Map.of("id", storeId.toString(), "name", "Boutique Sync", "type", "STORE"), Instant.now());
    }

    // ── Item 1: employee plan limit ───────────────────────────────────────────

    @Test
    @DisplayName("employee limit: at max−1, B blocks on employee-limit lock, then plan-limit refusal, rolled back")
    void employeeLimit_concurrentCreate_exactlyOne() throws Exception {
        String schema = freeTenantWithoutActiveStore();
        UUID store = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {  // FREE: 3 employees max → 2 active = max−1
            jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name) "
                    + "VALUES (?, ?, 'Emp', 'Existant')", UUID.randomUUID(), store);
        }
        String phoneB = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> b = race(schema, "employee-limit:" + schema,
                    "INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name) "
                            + "VALUES (?1, ?2, 'Emp', 'A')",
                    new Object[]{UUID.randomUUID(), store},
                    () -> createEmployee.execute(new CreateEmployeeCommand(
                            UUID.randomUUID(), phoneB, "Emp", "B", store)),
                    pool);

            assertThat(domainCode(failure(b))).isEqualTo("PLAN_LIMIT_EXCEEDED");
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".employees WHERE status = 'ACTIVE'"))
                    .isEqualTo(3);
            // Rolled back: no user nor membership left behind for B.
            assertThat(count("SELECT count(*) FROM public.users WHERE phone_number = ?", phoneB)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("v1s-14-13 employee limit: reactivation races a create at max−1 → B blocks, then refused; target stays INACTIVE")
    void employeeLimit_reactivateRacesCreate_refused() throws Exception {
        String schema = freeTenantWithoutActiveStore();
        UUID store = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {  // FREE: 3 employees max → 2 active = max−1
            jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name) "
                    + "VALUES (?, ?, 'Emp', 'Existant')", UUID.randomUUID(), store);
        }
        UUID target = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, status) "
                + "VALUES (?, ?, ?, 'Emp', 'Inactif', 'INACTIVE')", target, UUID.randomUUID(), store);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> b = race(schema, "employee-limit:" + schema,
                    "INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name) "
                            + "VALUES (?1, ?2, 'Emp', 'A')",
                    new Object[]{UUID.randomUUID(), store},
                    () -> {
                        authenticateOwner();
                        reactivateEmployee.execute(new ReactivateEmployeeCommand(UUID.randomUUID(), target));
                        return null;
                    },
                    pool);

            assertThat(domainCode(failure(b))).isEqualTo("PLAN_LIMIT_EXCEEDED");
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".employees WHERE status = 'ACTIVE'"))
                    .isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".employees WHERE id = ?",
                    String.class, target)).isEqualTo("INACTIVE");
        } finally {
            pool.shutdownNow();
        }
    }

    // ── v1s-14-13: product plan limit on inventory quick-add ──────────────────

    @Test
    @DisplayName("v1s-14-13 product limit: quick-add races a create at max−1 → B blocks, then refused; nothing written")
    void productLimit_quickAddRacesCreate_refused() throws Exception {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE'");
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        // FREE: 500 products max → 499 ACTIVE = max−1
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, status) "
                + "SELECT gen_random_uuid(), 'Seed ' || g, 'SEED-' || g, 'ACTIVE' FROM generate_series(1, 499) g");
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".inventory_sessions (id, store_id, scope, status, started_by, "
                + "started_at) VALUES (?, ?, 'FULL', 'IN_PROGRESS', ?, now())", session, store, UUID.randomUUID());
        String nameB = "Pagne quick-add " + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> b = race(schema, "product-limit:" + schema,
                    "INSERT INTO \"" + schema + "\".products (id, name, sku, status) "
                            + "VALUES (?1, 'Produit A', 'KEV-RACEA1', 'ACTIVE')",
                    new Object[]{UUID.randomUUID()},
                    () -> quickAddProduct.execute(new QuickAddProductCommand(
                            session, nameB, null, 3, null, UUID.randomUUID())),
                    pool);

            assertThat(domainCode(failure(b))).isEqualTo("PLAN_LIMIT_EXCEEDED");
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".products WHERE status = 'ACTIVE'"))
                    .isEqualTo(500);
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".products WHERE name = ?", nameB)).isZero();
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".inventory_counts WHERE session_id = ?",
                    session)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Item 2: weekly report scheduler vs manual trigger ─────────────────────

    @Test
    @DisplayName("weekly report: B blocks on weekly-report lock, then returns A's report; one WEEKLY row")
    void weeklyReport_schedulerVsManual_oneReport() throws Exception {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        LocalDate sunday = LocalDate.of(2026, 3, 29);
        Instant weekEnd = sunday.atTime(20, 0).atZone(WAT).toInstant();
        UUID reportA = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<EndOfDayReport> b = race(schema, "weekly-report:" + store + ":" + sunday,
                    "INSERT INTO \"" + schema + "\".reports (id, tenant_id, store_id, report_type, report_date, "
                            + "content, delivery_status, is_automatic) "
                            + "VALUES (?1, ?2, ?3, 'WEEKLY', ?4, 'rapport A', 'IN_APP_ONLY', TRUE)",
                    new Object[]{reportA, schema, store, sunday},
                    () -> weeklyReportGenerator.generateWeeklyReport(new WeeklyReportCommand(
                            store, schema, false, sunday.minusDays(6).atStartOfDay(WAT).toInstant(), weekEnd,
                            ReportChannel.IN_APP_ONLY, null, weekEnd)),
                    pool);

            assertThat(outcome(b).getId()).isEqualTo(reportA);
            assertThat(count("SELECT count(*) FROM \"" + schema + "\".reports WHERE store_id = ? "
                    + "AND report_type = 'WEEKLY' AND report_date = ?", store, sunday)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("weekly report via the production bean: after-commit delivery status is persisted (not PENDING)")
    void weeklyReport_afterCommitDelivery_statusPersisted() {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        LocalDate sunday = LocalDate.of(2026, 4, 5);
        Instant weekEnd = sunday.atTime(20, 0).atZone(WAT).toInstant();

        TenantContext.setCurrentTenant(schema);
        EndOfDayReport report = weeklyReportGenerator.generateWeeklyReport(new WeeklyReportCommand(
                store, schema, true, sunday.minusDays(6).atStartOfDay(WAT).toInstant(), weekEnd,
                null, null, weekEnd));
        TenantContext.clear();

        String status = jdbc.queryForObject("SELECT delivery_status FROM \"" + schema + "\".reports WHERE id = ?",
                String.class, report.getId());
        assertThat(status).isNotEqualTo("PENDING").isIn("SENT", "IN_APP_ONLY", "FAILED");
        assertThat(status).isEqualTo(report.getDeliveryStatus().name());
    }

    @Test
    @DisplayName("weekly report orphaned PENDING (crash before the after-commit send) is picked up by the retry query once stale")
    void weeklyReport_stalePending_isRetried() {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        String insert = "INSERT INTO \"" + schema + "\".reports (id, tenant_id, store_id, report_type, report_date, "
                + "content, delivery_status, is_automatic, created_at) VALUES (?, ?, ?, 'WEEKLY', ?, 'r', ?, TRUE, ?)";
        jdbc.update(insert, stale, schema, store, LocalDate.of(2026, 3, 1), "PENDING",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
        jdbc.update(insert, fresh, schema, store, LocalDate.of(2026, 3, 8), "PENDING", Timestamp.from(Instant.now()));
        jdbc.update(insert, failed, schema, store, LocalDate.of(2026, 3, 15), "FAILED", Timestamp.from(Instant.now()));

        TenantContext.setCurrentTenant(schema);
        List<UUID> ids;
        try {
            ids = tx.execute(s -> reportRepository.findPendingRetries(3).stream().map(EndOfDayReport::getId).toList());
        } finally {
            TenantContext.clear();
        }

        assertThat(ids).contains(stale, failed).doesNotContain(fresh);
    }

    // ── Item 3: deterministic SALE_CANCELLED / ADJUSTMENT movement ids ────────

    private record SeededSale(UUID saleId, UUID itemId, UUID productId) {}

    private SeededSale completedSale(String schema, UUID store, int qty) {
        String q = "\"" + schema + "\".";
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "products (id, name, sku, price) VALUES (?, ?, ?, 1000)",
                product, "Produit " + product, String.format("KEV-%06d", ThreadLocalRandom.current().nextInt(1_000_000)));
        jdbc.update("INSERT INTO " + q + "stock_levels (product_id, store_id, quantity) VALUES (?, ?, 50)",
                product, store);
        UUID sale = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + q + "sales (id, store_id, employee_id, total_amount, payment_mode, created_at, "
                        + "status, occurred_at, discount_amount) VALUES (?, ?, ?, ?, 'CASH', ?, 'COMPLETED', ?, 0)",
                sale, store, UUID.randomUUID(), 1000 * qty, now, now);
        jdbc.update("INSERT INTO " + q + "sale_items (id, sale_id, product_id, product_name, applied_unit_price, "
                        + "quantity, subtotal, catalogue_unit_price) VALUES (?, ?, ?, 'Produit', 1000, ?, ?, 1000)",
                item, sale, product, qty, 1000 * qty);
        return new SeededSale(sale, item, product);
    }

    private List<Map<String, Object>> movements(String schema, UUID product) {
        return jdbc.queryForList("SELECT id, movement_type, quantity_change FROM \"" + schema
                + "\".stock_movements WHERE product_id = ?", product);
    }

    @Test
    @DisplayName("owner cancel/correct of a COMPLETED sale write the DeterministicIds movement ids")
    void cancelAndCorrect_writeDeterministicMovementIds() {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        SeededSale cancelled = completedSale(schema, store, 2);
        SeededSale corrected = completedSale(schema, store, 3);
        UUID correctionId = UUID.randomUUID();

        TenantContext.setCurrentTenant(schema);
        cancelSale.cancelPendingSale(new CancelPendingSaleCommand(
                cancelled.saleId(), UUID.randomUUID(), null, JUSTIFICATION));
        correctSale.correctSale(new CorrectSaleCommand(corrected.saleId(), UUID.randomUUID(), null,
                JUSTIFICATION, Map.of(corrected.itemId(), 2), correctionId));
        TenantContext.clear();

        assertThat(movements(schema, cancelled.productId())).singleElement().satisfies(m -> {
            assertThat(m.get("id")).isEqualTo(DeterministicIds.saleCancelledMovementId(cancelled.itemId()));
            assertThat(m.get("movement_type")).isEqualTo("SALE_CANCELLED");
            assertThat(m.get("quantity_change")).isEqualTo(2);
        });
        assertThat(movements(schema, corrected.productId())).singleElement().satisfies(m -> {
            assertThat(m.get("id")).isEqualTo(
                    DeterministicIds.saleCorrectionMovementId(correctionId, corrected.itemId()));
            assertThat(m.get("movement_type")).isEqualTo("ADJUSTMENT");
            assertThat(m.get("quantity_change")).isEqualTo(1);
        });
    }

    // ── Item 4: commit lag vs the closure / weekly arrival limit ──────────────

    @Test
    @DisplayName("commit lag: a sale stamped R−1 s and committed after closure R is in the next window only")
    void commitLag_lateCommittedSale_landsInTheNextWindowExactlyOnce() throws Exception {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        Instant r = LocalDate.of(2026, 3, 15).atTime(12, 0).atZone(WAT).toInstant();
        Instant rNext = r.plus(Duration.ofHours(8));
        ClosureWindow window = ClosureWindow.forClosure(r, ClosureWindow.receivedLimit(r), Optional.empty());
        DayClosure closure = new DayClosure(UUID.randomUUID(), store, null, r, null, false, schema,
                ClosureWindow.receivedLimit(r));
        ClosureWindow next = ClosureWindow.forClosure(rNext, ClosureWindow.receivedLimit(rNext), Optional.of(closure.toView()));

        UUID lagging = UUID.randomUUID();
        UUID onTime = UUID.randomUUID();
        insertSaleCommitted(schema, onTime, store, r.minusSeconds(6));
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO \"" + schema + "\".sales (id, store_id, "
                    + "employee_id, total_amount, payment_mode, created_at, status, occurred_at, discount_amount) "
                    + "VALUES (?, ?, ?, 1000, 'CASH', ?, 'COMPLETED', ?, 0)")) {
                Timestamp stamped = Timestamp.from(r.minusSeconds(1));
                ps.setObject(1, lagging);
                ps.setObject(2, store);
                ps.setObject(3, UUID.randomUUID());
                ps.setTimestamp(4, stamped);
                ps.setTimestamp(5, stamped);
                ps.executeUpdate();
            }
            // Report R runs while the sale is still uncommitted.
            assertThat(saleIds(schema, store, window)).contains(onTime).doesNotContain(lagging);
            conn.commit();
        }

        // Even once committed, R's arrival limit (R − 5 s) excludes it; the chained next window
        // (lateFrom = R − 5 s) takes it, and only it takes it.
        assertThat(saleIds(schema, store, window)).contains(onTime).doesNotContain(lagging);
        assertThat(saleIds(schema, store, next)).containsOnlyOnce(lagging).doesNotContain(onTime);
    }

    private void insertSaleCommitted(String schema, UUID id, UUID store, Instant at) {
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, store_id, employee_id, total_amount, payment_mode, "
                        + "created_at, status, occurred_at, discount_amount) VALUES (?, ?, ?, 1000, 'CASH', ?, "
                        + "'COMPLETED', ?, 0)",
                id, store, UUID.randomUUID(), Timestamp.from(at), Timestamp.from(at));
    }

    private List<UUID> saleIds(String schema, UUID store, ClosureWindow window) {
        TenantContext.setCurrentTenant(schema);
        try {
            return tx.execute(s -> saleRepository.findForClosure(store, window).stream().map(Sale::getId).toList());
        } finally {
            TenantContext.clear();
        }
    }
}
