package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Page;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import com.keevo.shared.application.port.sync.PullScope;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class StockLevelDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code stock_levels} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    /**
     * Story 16.13 (reprise 10/11/2026, D1) — bound-parameter filter of a restricted (store member) pull: only the
     * member's store levels (the inter-store availability kept on 2026-11-01 is withdrawn by the user decision).
     */
    static final String STORE_SCOPE = "store_id = :scopeStoreId";

    private final EntityManager em;

    public StockLevelDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String moduleId() {
        return "inventory"; // Story 13.10a review: skipped by the pull while DISABLED
    }

    @Override
    public String entityKey() {
        return "stockLevels";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        return queryDeltaPage(since, after, limit, PullScope.NONE); // fail closed: the pull always passes its scope
    }

    /** Story 16.13 (reprise) — a store member only pulls the stock levels of its assigned store; the owner all. */
    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit, PullScope scope) {
        var page = DeltaPageQuery.fetchForStore(em,
                "id, product_id, variant_id, store_id, quantity, COALESCE(minimum_threshold, 0) AS minimum_threshold, updated_at",
                "stock_levels", CURSOR, since, after, limit, scope, STORE_SCOPE);
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
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
