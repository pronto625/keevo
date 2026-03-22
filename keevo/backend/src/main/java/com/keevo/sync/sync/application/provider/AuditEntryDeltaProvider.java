package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class AuditEntryDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public AuditEntryDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "auditEntries";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        // First-time sync: limit to last 30 days
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant effectiveSince = since.isAfter(thirtyDaysAgo) ? since : thirtyDaysAgo;

        String sql = "SELECT id, user_id, entity_type, entity_id, action, value_before, " +
                "value_after, occurred_at " +
                "FROM audit_log WHERE occurred_at > :since ORDER BY occurred_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(effectiveSince));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("userId", str(row[1]));
        map.put("entityType", str(row[2]));
        map.put("entityId", str(row[3]));
        map.put("action", str(row[4]));
        map.put("valueBefore", str(row[5]));
        map.put("valueAfter", str(row[6]));
        map.put("occurredAt", ts(row[7]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
