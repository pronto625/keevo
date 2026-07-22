package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ReportsDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public ReportsDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "reports";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, tenant_id, store_id, store_name, actor_id, " +
                "actor_name, report_type, report_date, content, delivery_status, " +
                "delivery_attempts, last_attempt_at, total_revenue, total_sales, " +
                "is_automatic, created_at " +
                "FROM reports WHERE created_at > :since ORDER BY created_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(since));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("tenantId", str(row[1]));
        map.put("storeId", str(row[2]));
        map.put("storeName", str(row[3]));
        map.put("actorId", str(row[4]));
        map.put("actorName", str(row[5]));
        map.put("reportType", str(row[6]));
        map.put("reportDate", str(row[7]));
        map.put("content", str(row[8]));
        map.put("deliveryStatus", str(row[9]));
        map.put("deliveryAttempts", num(row[10]));
        map.put("lastAttemptAt", ts(row[11]));
        map.put("totalRevenue", num(row[12]));
        map.put("totalSales", num(row[13]));
        map.put("isAutomatic", bool(row[14]));
        map.put("createdAt", ts(row[15]));
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
