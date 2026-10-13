package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class StockLevelDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code stock_levels} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    private final EntityManager em;

    public StockLevelDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "stockLevels";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, product_id, variant_id, store_id, quantity, COALESCE(minimum_threshold, 0) AS minimum_threshold, updated_at",
                "stock_levels", CURSOR, since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("productId", str(row[1]));
        map.put("variantId", str(row[2]));
        map.put("storeId", str(row[3]));
        map.put("quantity", DeltaValues.num(row[4]));
        map.put("minimumThreshold", DeltaValues.num(row[5]));
        map.put("updatedAt", ts(row[6]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
