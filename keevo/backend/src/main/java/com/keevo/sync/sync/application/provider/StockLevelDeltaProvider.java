package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class StockLevelDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public StockLevelDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "stockLevels";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, product_id, variant_id, store_id, quantity, " +
                "COALESCE(minimum_threshold, 0) AS minimum_threshold, updated_at " +
                "FROM stock_levels WHERE updated_at > :since";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(since));
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
        map.put("quantity", num(row[4]));
        map.put("minimumThreshold", num(row[5]));
        map.put("updatedAt", ts(row[6]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private Object num(Object o) { return o != null ? ((Number) o).intValue() : 0; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
