package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefCustomer;
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
import java.util.List;
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

    private final ObjectMapper mapper;
    private final String url;
    private final String username;
    private final String password;
    private volatile HikariDataSource dataSource;

    JdbcBrefSource(ObjectMapper mapper,
                   @Value("${keevo.migration.bref.jdbc.url:}") String url,
                   @Value("${keevo.migration.bref.jdbc.username:}") String username,
                   @Value("${keevo.migration.bref.jdbc.password:}") String password) {
        this.mapper = mapper;
        this.url = url == null ? "" : url.strip();
        this.username = username;
        this.password = password;
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
        }
        return new BrefSnapshot(owner, shops, members, products, customers, SourceKind.DATABASE);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<String> rows(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
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
                    config.setMaximumPoolSize(2);
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

    @PreDestroy
    void close() {
        HikariDataSource ds = dataSource;
        if (ds != null) {
            ds.close();
        }
    }
}
