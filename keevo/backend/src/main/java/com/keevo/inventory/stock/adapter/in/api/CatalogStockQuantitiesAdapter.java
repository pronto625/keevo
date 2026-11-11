package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.catalog.api.ProductStockQuantities;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 16.13 (reprise 10/11/2026) — inventory's implementation of the catalogue's {@link ProductStockQuantities}: one
 * grouped query over {@code stock_levels} for the given product ids (index {@code (product_id, store_id)}), optionally
 * limited to one store; summed in 64 bits, capped to {@code int}.
 */
@Component
class CatalogStockQuantitiesAdapter implements ProductStockQuantities {

    /** One statement whatever the number of ids (array parameter); only active stores, like the overview. */
    static final String SQL_TPL = "SELECT sl.product_id, SUM(CAST(sl.quantity AS bigint)) AS qty "
            + "FROM %1$s.stock_levels sl JOIN %1$s.stores s ON s.id = sl.store_id AND s.is_active = true "
            + "WHERE sl.product_id = ANY(CAST(? AS uuid[]))%2$s GROUP BY sl.product_id";

    private final JdbcTemplate jdbc;

    CatalogStockQuantitiesAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> quantities(String tenantSchema, Collection<UUID> productIds, UUID onlyStoreId) {
        TenantContext.requireCurrent(tenantSchema);
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        String idArray = productIds.stream().distinct().map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
        String sql = String.format(SQL_TPL, TenantSchema.validate(tenantSchema),
                onlyStoreId != null ? " AND sl.store_id = CAST(? AS uuid)" : "");
        List<Object> args = new ArrayList<>(2);
        args.add(idArray);
        if (onlyStoreId != null) {
            args.add(onlyStoreId.toString());
        }
        Map<UUID, Integer> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            long qty = rs.getLong("qty");
            out.put(UUID.fromString(rs.getString("product_id")),
                    (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, qty)));
        }, args.toArray());
        return out;
    }
}
