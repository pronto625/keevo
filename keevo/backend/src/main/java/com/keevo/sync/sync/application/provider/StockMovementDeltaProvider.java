package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class StockMovementDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public StockMovementDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "stockMovements";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        // First-time sync: limit to last 30 days (match local purge policy)
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant effectiveSince = since.isAfter(thirtyDaysAgo) ? since : thirtyDaysAgo;

        String sql = "SELECT id, product_id, variant_id, store_id, movement_type, " +
                "quantity_before, quantity_change, quantity_after, actor_id, notes, occurred_at " +
                "FROM stock_movements WHERE occurred_at > :since ORDER BY occurred_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(effectiveSince));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("productId", str(row[1]));
        map.put("variantId", str(row[2]));
        map.put("storeId", str(row[3]));
        map.put("movementType", str(row[4]));
        map.put("quantityBefore", num(row[5]));
        map.put("quantityChange", num(row[6]));
        map.put("quantityAfter", num(row[7]));
        map.put("actorId", str(row[8]));
        map.put("notes", str(row[9]));
        map.put("occurredAt", ts(row[10]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private Object num(Object o) { return o != null ? ((Number) o).intValue() : 0; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
