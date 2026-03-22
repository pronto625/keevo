package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class SaleDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public SaleDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "sales";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String saleSql = "SELECT id, store_id, employee_id, client_id, total_amount, " +
                "COALESCE(discount_amount, 0) AS discount_amount, payment_mode, " +
                "COALESCE(status, 'COMPLETED') AS status, occurred_at, created_at " +
                "FROM sales WHERE created_at > :since ORDER BY created_at ASC";
        Query saleQuery = em.createNativeQuery(saleSql);
        saleQuery.setParameter("since", java.sql.Timestamp.from(since));
        saleQuery.setMaxResults(1000);
        List<Object[]> saleRows = saleQuery.getResultList();

        if (saleRows.isEmpty()) return List.of();

        // Build sale maps and collect IDs for batch item query
        List<String> saleIds = new ArrayList<>();
        Map<String, Map<String, Object>> saleMaps = new LinkedHashMap<>();
        for (Object[] row : saleRows) {
            Map<String, Object> map = mapSale(row);
            String saleId = (String) map.get("id");
            saleIds.add(saleId);
            map.put("items", new ArrayList<>());
            saleMaps.put(saleId, map);
        }

        // Batch-load all sale items in one query (eliminates N+1)
        String itemSql = "SELECT si.sale_id, si.id, si.product_id, si.variant_id, si.product_name, " +
                "si.applied_unit_price, COALESCE(si.catalogue_unit_price, si.applied_unit_price) AS catalogue_unit_price, " +
                "si.quantity, si.subtotal FROM sale_items si " +
                "WHERE si.sale_id IN (:saleIds)";
        Query itemQuery = em.createNativeQuery(itemSql);
        itemQuery.setParameter("saleIds", saleIds.stream()
                .map(java.util.UUID::fromString).toList());
        List<Object[]> itemRows = itemQuery.getResultList();

        for (Object[] itemRow : itemRows) {
            String parentSaleId = str(itemRow[0]);
            Map<String, Object> saleMap = saleMaps.get(parentSaleId);
            if (saleMap != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) saleMap.get("items");
                items.add(mapItem(itemRow));
            }
        }

        return new ArrayList<>(saleMaps.values());
    }

    private Map<String, Object> mapSale(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("storeId", str(row[1]));
        map.put("employeeId", str(row[2]));
        map.put("clientId", str(row[3]));
        map.put("totalAmount", num(row[4]));
        map.put("discountAmount", num(row[5]));
        map.put("paymentMode", str(row[6]));
        map.put("status", str(row[7]));
        map.put("occurredAt", ts(row[8]));
        map.put("createdAt", ts(row[9]));
        return map;
    }

    private Map<String, Object> mapItem(Object[] row) {
        // row[0] = sale_id (used for grouping), item fields start at index 1
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[1]));
        map.put("productId", str(row[2]));
        map.put("variantId", str(row[3]));
        map.put("productName", str(row[4]));
        map.put("appliedUnitPrice", num(row[5]));
        map.put("catalogueUnitPrice", num(row[6]));
        map.put("quantity", num(row[7]));
        map.put("subtotal", num(row[8]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private Object num(Object o) { return o != null ? ((Number) o).intValue() : 0; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
