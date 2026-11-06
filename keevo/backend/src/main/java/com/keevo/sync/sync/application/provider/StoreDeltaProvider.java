package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Page;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import com.keevo.store.api.StoreLogoUrls;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class StoreDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code stores} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    private final EntityManager em;

    public StoreDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String moduleId() {
        return "store"; // Story 13.10a review: skipped by the pull while DISABLED
    }

    @Override
    public String entityKey() {
        return "stores";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, name, address, phone, type, is_active, created_at, updated_at,"
                        + " category, description, logo_path, niu, rccm, country, share_clients",
                "stores", CURSOR, since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("name", str(row[1]));
        map.put("address", str(row[2]));
        map.put("phone", str(row[3]));
        map.put("type", str(row[4]));
        map.put("isActive", row[5]);
        map.put("createdAt", ts(row[6]));
        map.put("updatedAt", ts(row[7]));
        // Story 19.12 — additive Bref fields; logoUrl is the stable API path (never the disk path)
        map.put("category", str(row[8]));
        map.put("description", str(row[9]));
        map.put("logoUrl", StoreLogoUrls.of(row[0] == null ? null : UUID.fromString(str(row[0])), str(row[10])));
        map.put("niu", str(row[11]));
        map.put("rccm", str(row[12]));
        map.put("country", str(row[13]));
        map.put("shareClients", Boolean.TRUE.equals(row[14])); // Story 20.2 (ADR-08)
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
