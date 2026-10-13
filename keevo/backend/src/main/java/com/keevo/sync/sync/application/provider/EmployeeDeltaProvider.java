package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class EmployeeDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code employees} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    private final EntityManager em;

    public EmployeeDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "employees";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, user_id, first_name, last_name, store_id, status, password_change_required, created_at, updated_at",
                "employees", CURSOR, since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("userId", str(row[1]));
        map.put("firstName", str(row[2]));
        map.put("lastName", str(row[3]));
        map.put("storeId", str(row[4]));
        map.put("status", str(row[5]));
        map.put("passwordChangeRequired", row[6]);
        map.put("createdAt", ts(row[7]));
        map.put("updatedAt", ts(row[8]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
