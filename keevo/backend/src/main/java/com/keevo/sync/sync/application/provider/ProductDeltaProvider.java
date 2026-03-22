package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ProductDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public ProductDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "products";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, name, price, buy_price, transport_cost, sku, category_id, " +
                "description, photo_url, archived, status, stock_quantity, created_at, updated_at " +
                "FROM products WHERE updated_at > :since ORDER BY updated_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(since));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("name", str(row[1]));
        map.put("price", num(row[2]));
        map.put("buyPrice", num(row[3]));
        map.put("transportCost", num(row[4]));
        map.put("sku", str(row[5]));
        map.put("categoryId", str(row[6]));
        map.put("description", str(row[7]));
        map.put("photoUrl", str(row[8]));
        map.put("archived", row[9]);
        map.put("status", str(row[10]));
        map.put("stockQuantity", num(row[11]));
        map.put("createdAt", ts(row[12]));
        map.put("updatedAt", ts(row[13]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private Object num(Object o) { return o != null ? ((Number) o).intValue() : 0; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
