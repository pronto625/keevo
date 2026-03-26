package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * InventoryCountDeltaProvider — Pull sync delta provider for inventory_counts.
 */
@Component
public class InventoryCountDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public InventoryCountDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "inventoryCounts";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, session_id, product_id, variant_id, product_name, " +
                "variant_label, theoretical, physical, counted_at, counted_by, updated_at " +
                "FROM inventory_counts WHERE updated_at > :since ORDER BY updated_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(since));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("sessionId", str(row[1]));
        map.put("productId", str(row[2]));
        map.put("variantId", str(row[3]));
        map.put("productName", str(row[4]));
        map.put("variantLabel", str(row[5]));
        map.put("theoretical", row[6]);
        map.put("physical", row[7]);
        map.put("countedAt", ts(row[8]));
        map.put("countedBy", str(row[9]));
        map.put("updatedAt", ts(row[10]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
