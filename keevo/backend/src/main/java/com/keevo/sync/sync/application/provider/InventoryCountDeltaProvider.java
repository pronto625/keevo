package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * InventoryCountDeltaProvider — Pull sync delta provider for inventory_counts.
 */
@Component
public class InventoryCountDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code inventory_counts} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    private final EntityManager em;

    public InventoryCountDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "inventoryCounts";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, session_id, product_id, variant_id, product_name, variant_label, theoretical, physical, counted_at, counted_by, updated_at",
                "inventory_counts", CURSOR, since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("sessionId", str(row[1]));
        map.put("productId", str(row[2]));
        map.put("variantId", str(row[3]));
        map.put("productName", str(row[4]));
        map.put("variantLabel", str(row[5]));
        map.put("theoretical", row[6]);
        map.put("physical", row[7]);
        map.put("countedAt", ts(row[8]));
        map.put("countedBy", str(row[9]));
        map.put("updatedAt", ts(row[10]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
