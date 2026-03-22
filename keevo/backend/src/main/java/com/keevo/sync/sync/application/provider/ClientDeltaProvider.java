package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ClientDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public ClientDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "clients";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, name, phone, email, notes, archived, created_at, updated_at " +
                "FROM clients WHERE updated_at > :since";
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
        map.put("phone", str(row[2]));
        map.put("email", str(row[3]));
        map.put("notes", str(row[4]));
        map.put("archived", row[5]);
        map.put("createdAt", ts(row[6]));
        map.put("updatedAt", ts(row[7]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
