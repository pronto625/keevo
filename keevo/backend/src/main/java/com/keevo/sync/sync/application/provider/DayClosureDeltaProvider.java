package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class DayClosureDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public DayClosureDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "dayClosures";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, store_id, actor_id, closed_at, " +
                "total_sales, total_revenue, " +
                "cash_amount, momo_amount, is_automatic " +
                "FROM day_closures WHERE closed_at > :since ORDER BY closed_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(since));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("storeId", str(row[1]));
        map.put("employeeId", str(row[2]));
        map.put("closedAt", ts(row[3]));
        map.put("totalSales", num(row[4]));
        map.put("totalRevenue", num(row[5]));
        map.put("cashTotal", num(row[6]));
        map.put("mobileMoneyTotal", num(row[7]));
        map.put("isAutomatic", row.length > 8 ? bool(row[8]) : false);
        map.put("createdAt", ts(row[3])); // closedAt serves as createdAt
        return map;
    }

    private boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && ((Number) o).intValue() != 0;
    }
    

    private String str(Object o) { return o != null ? o.toString() : null; }
    private Object num(Object o) { return o != null ? ((Number) o).intValue() : 0; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
