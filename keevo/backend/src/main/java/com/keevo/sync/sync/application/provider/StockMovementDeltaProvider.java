package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class StockMovementDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code stock_movements} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "occurred_at";

    private final EntityManager em;

    public StockMovementDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "stockMovements";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        // First-time sync: limit to last 30 days (match local purge policy)
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant effectiveSince = since.isAfter(thirtyDaysAgo) ? since : thirtyDaysAgo;

        var page = DeltaPageQuery.fetch(em,
                "id, product_id, variant_id, store_id, movement_type, quantity_before, quantity_change, quantity_after, actor_id, notes, occurred_at",
                "stock_movements", CURSOR, effectiveSince, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("productId", str(row[1]));
        map.put("variantId", str(row[2]));
        map.put("storeId", str(row[3]));
        map.put("movementType", str(row[4]));
        map.put("quantityBefore", DeltaValues.num(row[5]));
        map.put("quantityChange", DeltaValues.num(row[6]));
        map.put("quantityAfter", DeltaValues.num(row[7]));
        map.put("actorId", str(row[8]));
        map.put("notes", str(row[9]));
        map.put("occurredAt", ts(row[10]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
