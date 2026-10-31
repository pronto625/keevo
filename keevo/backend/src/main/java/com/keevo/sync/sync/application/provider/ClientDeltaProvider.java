package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Page;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ClientDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code clients} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    private final EntityManager em;

    public ClientDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String moduleId() {
        return "crm"; // Story 13.10a review: skipped by the pull while DISABLED
    }

    @Override
    public String entityKey() {
        return "clients";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, name, phone, email, notes, archived, created_at, updated_at",
                "clients", CURSOR, since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
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
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
