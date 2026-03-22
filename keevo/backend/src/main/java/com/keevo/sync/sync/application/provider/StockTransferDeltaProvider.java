package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class StockTransferDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public StockTransferDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "stockTransfers";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, source_store_id, destination_store_id, product_id, " +
                "variant_id, quantity, actor_id, occurred_at, status, notes " +
                "FROM stock_transfers WHERE GREATEST(occurred_at, COALESCE(updated_at, occurred_at)) > :since " +
                "ORDER BY occurred_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(since));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("sourceStoreId", str(row[1]));
        map.put("destinationStoreId", str(row[2]));
        map.put("productId", str(row[3]));
        map.put("variantId", str(row[4]));
        map.put("quantity", num(row[5]));
        map.put("actorId", str(row[6]));
        map.put("occurredAt", ts(row[7]));
        map.put("status", str(row[8]));
        map.put("notes", str(row[9]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private Object num(Object o) { return o != null ? ((Number) o).intValue() : 0; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
