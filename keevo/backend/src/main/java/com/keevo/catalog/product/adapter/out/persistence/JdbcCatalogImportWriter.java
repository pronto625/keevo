package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.catalog.api.CatalogImportPort.ImportedProduct;
import com.keevo.catalog.product.domain.port.out.CatalogImportWriter;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.3 — {@link CatalogImportWriter} as set-based statements on the explicit, validated schema
 * ({@code INSERT … SELECT FROM unnest(…) … RETURNING id}, one round trip each; dates as ISO-8601 text cast to
 * {@code timestamptz}). Imported products are untracked, active, with no stock nor cost.
 */
@Component
class JdbcCatalogImportWriter implements CatalogImportWriter {

    private final JdbcTemplate jdbc;

    JdbcCatalogImportWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String table(String tenantSchema, String table) {
        return "\"" + TenantSchema.validate(tenantSchema) + "\"." + table;
    }

    @Override
    public Map<String, UUID> findRootCategories(String tenantSchema, Collection<String> lowerNames) {
        Map<String, UUID> found = new LinkedHashMap<>();
        if (lowerNames.isEmpty()) {
            return found;
        }
        String sql = "SELECT lower(btrim(name)) AS n, id FROM " + table(tenantSchema, "categories")
                + " WHERE parent_id IS NULL AND lower(btrim(name)) = ANY (?::text[]) ORDER BY created_at, id";
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("text", lowerNames.toArray()));
            return ps;
        }, rs -> {
            found.putIfAbsent(rs.getString("n"), rs.getObject("id", UUID.class));
        });
        return found;
    }

    @Override
    public Set<UUID> insertCategories(String tenantSchema, List<NewCategory> categories) {
        if (categories.isEmpty()) {
            return Set.of();
        }
        String sql = "INSERT INTO " + table(tenantSchema, "categories") + " (id, name, parent_id, is_active, is_custom, "
                + "created_at, updated_at) SELECT id, name, NULL, true, is_custom, now(), now() "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::boolean[]) AS x(id, name, is_custom) "
                + "ON CONFLICT (id) DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", categories.stream().map(NewCategory::id).toArray()));
            ps.setArray(2, con.createArrayOf("varchar", categories.stream().map(NewCategory::name).toArray()));
            ps.setArray(3, con.createArrayOf("boolean", categories.stream().map(NewCategory::custom).toArray()));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    @Override
    public Set<String> existingSkus(String tenantSchema, Collection<String> skus) {
        if (skus.isEmpty()) {
            return Set.of();
        }
        String sql = "SELECT sku FROM " + table(tenantSchema, "products") + " WHERE sku = ANY (?::varchar[])";
        Set<String> found = new HashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("varchar", skus.toArray()));
            return ps;
        }, rs -> {
            found.add(rs.getString(1));
        });
        return found;
    }

    @Override
    public Set<UUID> insertProducts(String tenantSchema, List<ImportedProduct> products, List<String> skus) {
        if (products.isEmpty()) {
            return Set.of();
        }
        if (skus.size() != products.size()) {
            throw new IllegalArgumentException("one SKU per product");
        }
        // ON CONFLICT DO NOTHING (no target): an id already there or a name taken meanwhile skips the row; the
        // merged_into_id foreign key is checked at the end of the statement (targets inserted by the same statement)
        String sql = "INSERT INTO " + table(tenantSchema, "products") + " (id, name, sku, category_id, price, buy_price, "
                + "transport_cost, stock_quantity, minimum_threshold, track_stock, barcode, product_key, price_manual, "
                + "merged_into_id, archived, status, created_at, updated_at) "
                + "SELECT id, name, sku, category_id, price, 0, 0, 0, 0, false, barcode, product_key, price_manual, "
                + "merged_into_id, archived, 'ACTIVE', created_at::timestamptz, updated_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::varchar[], ?::uuid[], ?::int[], ?::varchar[], ?::varchar[], "
                + "?::boolean[], ?::uuid[], ?::boolean[], ?::text[], ?::text[]) AS x(id, name, sku, category_id, price, "
                + "barcode, product_key, price_manual, merged_into_id, archived, created_at, updated_at) "
                + "ON CONFLICT DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", column(products, ImportedProduct::id)));
            ps.setArray(2, con.createArrayOf("varchar", column(products, ImportedProduct::name)));
            ps.setArray(3, con.createArrayOf("varchar", skus.toArray()));
            ps.setArray(4, con.createArrayOf("uuid", column(products, ImportedProduct::categoryId)));
            ps.setArray(5, con.createArrayOf("integer", column(products, ImportedProduct::price)));
            ps.setArray(6, con.createArrayOf("varchar", column(products, ImportedProduct::barcode)));
            ps.setArray(7, con.createArrayOf("varchar", column(products, ImportedProduct::productKey)));
            ps.setArray(8, con.createArrayOf("boolean", column(products, ImportedProduct::priceManual)));
            ps.setArray(9, con.createArrayOf("uuid", column(products, ImportedProduct::mergedIntoId)));
            ps.setArray(10, con.createArrayOf("boolean", column(products, ImportedProduct::archived)));
            ps.setArray(11, con.createArrayOf("text", column(products, p -> p.createdAt().toString())));
            ps.setArray(12, con.createArrayOf("text", column(products, p -> p.updatedAt().toString())));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    @Override
    public Set<String> productNames(String tenantSchema) {
        return new HashSet<>(jdbc.queryForList("SELECT lower(name) FROM " + table(tenantSchema, "products"),
                String.class));
    }

    @Override
    public Set<UUID> existingProducts(String tenantSchema, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        String sql = "SELECT id FROM " + table(tenantSchema, "products") + " WHERE id = ANY (?::uuid[])";
        Set<UUID> found = new HashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, rs -> {
            found.add(rs.getObject(1, UUID.class));
        });
        return found;
    }

    private static Object[] column(List<ImportedProduct> products, Function<ImportedProduct, Object> field) {
        return products.stream().map(field).toArray();
    }
}
