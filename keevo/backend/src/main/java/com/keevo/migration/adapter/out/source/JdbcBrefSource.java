package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefProduct;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefSourcePort;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 24.2 — {@link BrefSourcePort} over a restored Bref database (dry-run, tests), strictly read-only: its own small
 * pool (never a Spring {@code DataSource} bean, never Keevo's database), read-only connections, one
 * {@code REPEATABLE READ} read-only transaction per owner (the three reads see the same snapshot), rolled back.
 *
 * <p>Rows are read as {@code to_jsonb(row)} and mapped by {@link BrefRows}, the export's own mapping: both sources give
 * the same snapshot, and a column an older Bref schema lacks reads as absent. Three queries per owner (owner by
 * primary key, shops by owner, members by shop — Bref has no index on {@code user.shopId}: a scan of its user table,
 * acceptable on a dry-run copy). Story 24.3 adds two indexed reads: products and customers of the owner's shops.
 */
@Component
class JdbcBrefSource implements BrefSourcePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcBrefSource.class);

    static final String OWNER_SQL = "SELECT to_jsonb(u)::text FROM \"user\" u WHERE u.id = ?::uuid";
    static final String SHOPS_SQL = "SELECT to_jsonb(s)::text FROM shop s WHERE s.\"userId\" = ?::uuid "
            + "ORDER BY s.\"createdAt\", s.id";
    static final String MEMBERS_SQL = "SELECT to_jsonb(u)::text FROM \"user\" u "
            + "WHERE lower(btrim(u.\"shopId\")) = ANY (?::text[]) AND u.id <> ?::uuid ORDER BY u.\"createdAt\", u.id";

    /**
     * Story 24.3 — the products of the owner's shops. Bref keeps {@code product.shopId} as text, compared trimmed and
     * lower-cased (any case or padding is the shop's, as for {@code user.shopId}): a scan of the product table per
     * owner, acceptable on a restored copy (24.9 may read all owners in one pass). Decimals travel as text (exact, as
     * the export writes them).
     */
    static final String PRODUCTS_SQL = "SELECT (to_jsonb(p) || jsonb_build_object('unitPrice', p.\"unitPrice\"::text, "
            + "'totalQuantity', p.\"totalQuantity\"::text, 'totalRevenue', p.\"totalRevenue\"::text))::text "
            + "FROM product p WHERE lower(btrim(p.\"shopId\")) = ANY (?::text[]) ORDER BY p.\"createdAt\", p.id";
    /** Story 24.3 — the customers of the owner's shops ({@code customer.shopId} is a uuid; index {@code (shopId, phone)}). */
    static final String CUSTOMERS_SQL = "SELECT (to_jsonb(c) || jsonb_build_object('totalSpent', "
            + "c.\"totalSpent\"::text))::text FROM customer c WHERE c.\"shopId\" = ANY (?::uuid[]) "
            + "ORDER BY c.\"createdAt\", c.id";

    /**
     * Story 24.4 — the documents of the owner's shops (sales and quotes, deleted ones included). Bref has no index on
     * {@code invoice.shopId}: a scan of the invoice table per owner, like the products (24.9 reads every owner in one
     * pass). Decimals as text (exact).
     */
    static final String INVOICES_SQL = "SELECT (to_jsonb(i) || jsonb_build_object('subtotal', i.subtotal::text, "
            + "'tax', i.tax::text, 'discount', i.discount::text, 'deliveryFee', i.\"deliveryFee\"::text, "
            + "'total', i.total::text, 'initialPayment', i.\"initialPayment\"::text))::text FROM invoice i "
            + "WHERE lower(btrim(i.\"shopId\")) = ANY (?::text[]) ORDER BY i.\"createdAt\", i.id";
    /**
     * Story 24.4 — the lines of the documents read by {@link #INVOICES_SQL} (their ids, so the invoice table is
     * scanned once per owner), in their stored order (Bref has no position nor date on a line: the physical order is
     * the order api-bref saved the line array in; Bref is stopped during the cutover, so it does not move between
     * runs). Bref has no index on {@code invoiceId}: one scan of the line table per owner (one-pass read: 24.9).
     */
    static final String ITEMS_SQL = "SELECT (to_jsonb(it) || jsonb_build_object('quantity', it.quantity::text, "
            + "'unitPrice', it.\"unitPrice\"::text, 'total', it.total::text))::text FROM invoice_item it "
            + "WHERE it.\"invoiceId\" = ANY (?::uuid[]) ORDER BY it.\"invoiceId\", it.ctid";
    /** Story 24.4 — the payments (instalments) of those documents. */
    static final String PAYMENTS_SQL = "SELECT (to_jsonb(p) || jsonb_build_object('amount', p.amount::text))::text "
            + "FROM invoice_payment p WHERE p.\"invoiceId\" = ANY (?::uuid[]) ORDER BY p.\"createdAt\", p.id";
    /**
     * Story 24.4 — the expenses of the owner's shops (deleted ones included); index {@code (shopId, expenseDate)} is
     * on the raw column, so this reads like the products (per-owner scan).
     */
    static final String EXPENSES_SQL = "SELECT (to_jsonb(e) || jsonb_build_object('amount', e.amount::text))::text "
            + "FROM expense e WHERE lower(btrim(e.\"shopId\")) = ANY (?::text[]) ORDER BY e.\"createdAt\", e.id";

    /**
     * Story 24.9 — rows fetched per round trip (the connection is never in auto-commit: the driver reads through a
     * cursor instead of buffering a whole result at once).
     */
    static final int FETCH_SIZE = 500;
    /** Story 24.9 — default pool: the cutover imports up to 8 owners at a time, each holding one read at a time. */
    static final int DEFAULT_POOL_SIZE = 8;

    private final ObjectMapper mapper;
    private final String url;
    private final String username;
    private final String password;
    private final int poolSize;
    private volatile HikariDataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    JdbcBrefSource(ObjectMapper mapper,
                   @Value("${keevo.migration.bref.jdbc.url:}") String url,
                   @Value("${keevo.migration.bref.jdbc.username:}") String username,
                   @Value("${keevo.migration.bref.jdbc.password:}") String password,
                   @Value("${keevo.migration.bref.jdbc.pool-size:" + DEFAULT_POOL_SIZE + "}") int poolSize) {
        this.mapper = mapper;
        this.url = url == null ? "" : url.strip();
        this.username = username;
        this.password = password;
        this.poolSize = Math.max(2, poolSize);
    }

    JdbcBrefSource(ObjectMapper mapper, String url, String username, String password) {
        this(mapper, url, username, password, DEFAULT_POOL_SIZE);
    }

    @Override
    public SourceKind kind() {
        return SourceKind.DATABASE;
    }

    @Override
    public boolean available() {
        return !url.isEmpty();
    }

    @Override
    public BrefSnapshot load(UUID ownerId) {
        if (!available()) {
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref database not configured");
        }
        try (Connection c = dataSource().getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return read(c, ownerId);
            } finally {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // read-only: nothing to undo; never hide the read's own outcome
                }
            }
        } catch (SQLException e) {
            log.warn("Bref source database unreachable or unreadable (SQLState {})", e.getSQLState());
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref database unreadable");
        } catch (IllegalArgumentException | IllegalStateException | java.time.DateTimeException e) {
            // a row the mapping cannot read (missing id or date, bad value): same answer as a malformed export
            throw new DomainException(ErrorCode.MIGRATION_EXPORT_INVALID, "Bref row unreadable");
        }
    }

    private BrefSnapshot read(Connection c, UUID ownerId) throws SQLException {
        List<String> owners = rows(c, OWNER_SQL, ps -> ps.setString(1, ownerId.toString()));
        if (owners.isEmpty()) {
            throw new DomainException(ErrorCode.MIGRATION_OWNER_NOT_FOUND, "Owner absent from the Bref database");
        }
        BrefAccount owner = BrefRows.account(json(owners.get(0)));
        if (!BrefMappings.BREF_OWNER_ROLE.equals(owner.role())) {
            throw new DomainException(ErrorCode.MIGRATION_OWNER_NOT_FOUND, "Not a Bref owner");
        }
        List<BrefShop> shops = new ArrayList<>();
        for (String row : rows(c, SHOPS_SQL, ps -> ps.setString(1, ownerId.toString()))) {
            shops.add(BrefRows.shop(json(row)));
        }
        List<BrefAccount> members = new ArrayList<>();
        List<BrefProduct> products = new ArrayList<>();
        List<BrefCustomer> customers = new ArrayList<>();
        List<BrefInvoice> invoices = new ArrayList<>();
        List<BrefExpense> expenses = new ArrayList<>();
        if (!shops.isEmpty()) {
            Object[] shopIds = shops.stream().map(s -> s.id().toString()).toArray();
            for (String row : rows(c, MEMBERS_SQL, ps -> {
                ps.setArray(1, c.createArrayOf("text", shopIds));
                ps.setString(2, ownerId.toString());
            })) {
                members.add(BrefRows.account(json(row)));
            }
            for (String row : rows(c, PRODUCTS_SQL, ps -> ps.setArray(1, c.createArrayOf("text", shopIds)))) {
                products.add(BrefRows.product(json(row)));
            }
            Object[] uuids = shops.stream().map(BrefShop::id).toArray();
            for (String row : rows(c, CUSTOMERS_SQL, ps -> ps.setArray(1, c.createArrayOf("uuid", uuids)))) {
                customers.add(BrefRows.customer(json(row)));
            }
            documents(c, shopIds, invoices, expenses);
        }
        return new BrefSnapshot(owner, shops, members, products, customers, invoices, expenses, SourceKind.DATABASE);
    }

    /** Story 24.4 — documents with their lines and payments (grouped by invoice in one pass), and expenses. */
    private void documents(Connection c, Object[] shopIds, List<BrefInvoice> invoices, List<BrefExpense> expenses)
            throws SQLException {
        List<com.fasterxml.jackson.databind.JsonNode> rows = new ArrayList<>();
        for (String row : rows(c, INVOICES_SQL, ps -> ps.setArray(1, c.createArrayOf("text", shopIds)))) {
            rows.add(json(row));
        }
        Map<UUID, List<BrefInvoiceItem>> items = new HashMap<>();
        Map<UUID, List<BrefInvoicePayment>> payments = new HashMap<>();
        if (!rows.isEmpty()) {
            Object[] ids = rows.stream().map(n -> UUID.fromString(n.path("id").asText())).toArray();
            for (String row : rows(c, ITEMS_SQL, ps -> ps.setArray(1, c.createArrayOf("uuid", ids)))) {
                BrefInvoiceItem item = BrefRows.invoiceItem(json(row), null);
                items.computeIfAbsent(item.invoiceId(), k -> new ArrayList<>()).add(item);
            }
            for (String row : rows(c, PAYMENTS_SQL, ps -> ps.setArray(1, c.createArrayOf("uuid", ids)))) {
                BrefInvoicePayment payment = BrefRows.invoicePayment(json(row), null);
                payments.computeIfAbsent(payment.invoiceId(), k -> new ArrayList<>()).add(payment);
            }
        }
        for (com.fasterxml.jackson.databind.JsonNode n : rows) {
            UUID id = UUID.fromString(n.path("id").asText());
            invoices.add(BrefRows.invoice(n, items.getOrDefault(id, List.of()),
                    payments.getOrDefault(id, List.of())));
        }
        for (String row : rows(c, EXPENSES_SQL, ps -> ps.setArray(1, c.createArrayOf("text", shopIds)))) {
            expenses.add(BrefRows.expense(json(row)));
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<String> rows(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
                return rows;
            }
        }
    }

    private com.fasterxml.jackson.databind.JsonNode json(String row) {
        try {
            return mapper.readTree(row);
        } catch (IOException e) {
            throw new IllegalStateException("Bref row unreadable", e);
        }
    }

    private HikariDataSource dataSource() {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            synchronized (this) {
                ds = dataSource;
                if (ds == null) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(url);
                    config.setUsername(username);
                    config.setPassword(password);
                    config.setReadOnly(true);
                    config.setMaximumPoolSize(poolSize);
                    config.setMinimumIdle(0);
                    config.setConnectionTimeout(5_000);
                    config.setPoolName("bref-source");
                    config.setInitializationFailTimeout(-1); // lazy: an unreachable source fails the call, not the boot
                    ds = new HikariDataSource(config);
                    dataSource = ds;
                }
            }
        }
        return ds;
    }

    /** Story 24.5 — work on one read-only connection of the source. */
    interface ReadOnlyWork<T> {
        T read(Connection c) throws SQLException;
    }

    /**
     * Story 24.5 — runs {@code work} in one read-only {@code REPEATABLE READ} transaction of this source's pool, rolled
     * back afterwards (same handling as {@link #load}): the other sections of the owner (monetisation) are read the
     * same way, on the same small pool.
     */
    <T> T readOnly(ReadOnlyWork<T> work) {
        if (!available()) {
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref database not configured");
        }
        try (Connection c = dataSource().getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return work.read(c);
            } finally {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // read-only: nothing to undo; never hide the read's own outcome
                }
            }
        } catch (SQLException e) {
            log.warn("Bref source database unreachable or unreadable (SQLState {})", e.getSQLState());
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref database unreadable");
        } catch (IllegalArgumentException | IllegalStateException | java.time.DateTimeException e) {
            throw new DomainException(ErrorCode.MIGRATION_EXPORT_INVALID, "Bref row unreadable");
        }
    }

    /** Story 24.5 — a row mapped to JSON (the shared mapping of {@link BrefRows}). */
    com.fasterxml.jackson.databind.JsonNode jsonRow(String row) {
        return json(row);
    }

    @PreDestroy
    void close() {
        HikariDataSource ds = dataSource;
        if (ds != null) {
            ds.close();
        }
    }
}
