package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Page;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.shared.infrastructure.security.PurchasePriceVisibility;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ProductDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code products} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    private final EntityManager em;

    public ProductDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String moduleId() {
        return "catalog"; // Story 13.10a review: skipped by the pull while DISABLED
    }

    @Override
    public String entityKey() {
        return "products";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        return queryDeltaPage(since, after, limit, PullScope.NONE); // fail closed: the pull always passes its scope
    }

    /**
     * Story 16.13 (reprise 10/11/2026, D2) — a pull without {@code PURCHASE_PRICE_VIEW} (EMPLOYEE, VIEWER) gets
     * {@code buyPrice: 0} and {@code transportCost: 0} plus {@code purchasePriceHidden: true}: V1 2.0.1 stores pulled
     * products in a NOT NULL column (a null would fail its pull); the RN app reads the flag. Same rows for everyone.
     */
    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit, PullScope scope) {
        boolean hidden = scope == null || !scope.holds(PurchasePriceVisibility.PERMISSION);
        var page = DeltaPageQuery.fetch(em,
                "id, name, price, buy_price, transport_cost, sku, category_id, description, photo_url, archived, status, stock_quantity, created_at, updated_at, minimum_threshold, track_stock, barcode, product_key, price_manual, merged_into_id",
                "products", CURSOR, since, after, limit);
        return new Page(page.rows().stream().map(row -> mapRow(row, hidden)).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row, boolean purchasePriceHidden) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("name", str(row[1]));
        map.put("price", DeltaValues.num(row[2]));
        map.put("buyPrice", purchasePriceHidden ? 0 : DeltaValues.num(row[3]));
        map.put("transportCost", purchasePriceHidden ? 0 : DeltaValues.num(row[4]));
        map.put("sku", str(row[5]));
        map.put("categoryId", str(row[6]));
        map.put("description", str(row[7]));
        map.put("photoUrl", str(row[8]));
        map.put("archived", row[9]);
        map.put("status", str(row[10]));
        map.put("stockQuantity", DeltaValues.num(row[11]));
        map.put("createdAt", ts(row[12]));
        map.put("updatedAt", ts(row[13]));
        // Product-wide low-stock threshold (where the backend stores it) — lets the app
        // apply it offline even in a store where the product has no stock_levels row yet.
        map.put("minimumThreshold", row.length > 14 ? DeltaValues.num(row[14]) : 0);
        // Story 17.12 — per-product stock tracking (false: sold without stock); a missing value means tracked.
        map.put("trackStock", row.length > 15 ? !Boolean.FALSE.equals(row[15]) : Boolean.TRUE);
        // Story 20.1 — additive: reference / barcode, dedup key, manual price, merge target (the device redirects a
        // merged product to its target; null when not merged)
        map.put("barcode", row.length > 16 ? str(row[16]) : null);
        map.put("productKey", row.length > 17 ? str(row[17]) : null);
        map.put("priceManual", row.length > 18 ? Boolean.TRUE.equals(row[18]) : Boolean.FALSE);
        map.put("mergedIntoId", row.length > 19 ? str(row[19]) : null);
        // Story 16.13 (reprise) — additive: true when buyPrice / transportCost are masked (0) for this reader
        map.put("purchasePriceHidden", purchasePriceHidden);
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
