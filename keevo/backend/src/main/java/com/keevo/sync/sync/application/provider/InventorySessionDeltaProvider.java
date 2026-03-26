package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * InventorySessionDeltaProvider — Pull sync delta provider for inventory_sessions.
 *
 * <p>Returns inventory sessions modified since the given timestamp for delta sync.
 */
@Component
public class InventorySessionDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public InventorySessionDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "inventorySessions";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, store_id, scope, category_ids, status, started_by, " +
                "started_at, cancelled_by, cancelled_at, completed_at, updated_at " +
                "FROM inventory_sessions WHERE updated_at > :since ORDER BY updated_at ASC";
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
        map.put("scope", str(row[2]));
        map.put("categoryIds", str(row[3]));
        map.put("status", str(row[4]));
        map.put("startedBy", str(row[5]));
        map.put("startedAt", ts(row[6]));
        map.put("cancelledBy", str(row[7]));
        map.put("cancelledAt", ts(row[8]));
        map.put("completedAt", ts(row[9]));
        map.put("updatedAt", ts(row[10]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
