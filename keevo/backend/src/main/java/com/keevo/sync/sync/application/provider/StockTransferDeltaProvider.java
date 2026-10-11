package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class StockTransferDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public StockTransferDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "stockTransfers";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, source_store_id, destination_store_id, product_id, variant_id, quantity, actor_id, occurred_at, status, notes",
                "stock_transfers", "GREATEST(occurred_at, COALESCE(updated_at, occurred_at))", since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("sourceStoreId", str(row[1]));
        map.put("destinationStoreId", str(row[2]));
        map.put("productId", str(row[3]));
        map.put("variantId", str(row[4]));
        map.put("quantity", DeltaValues.num(row[5]));
        map.put("actorId", str(row[6]));
        map.put("occurredAt", ts(row[7]));
        map.put("status", str(row[8]));
        map.put("notes", str(row[9]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
