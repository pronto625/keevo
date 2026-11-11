package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Page;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import com.keevo.shared.application.port.sync.PullScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class SaleDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code sales} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    /** Story 16.13 — bound-parameter filter applied to a restricted (EMPLOYEE) pull. */
    static final String STORE_SCOPE = "store_id = :scopeStoreId";

    /**
     * Story 19.14 (routed from 19.5b) — a member without {@value #STORE_SALES_PERMISSION} pulls his own sales of his
     * store only, as REST and the AI tool show them (keyset served by {@code idx_sales_store_employee_sync}, pos V3).
     */
    static final String OWN_SALES_SCOPE = "store_id = :scopeStoreId AND employee_id = :scopeActorId";

    /** « Voir les ventes de toute la boutique » (declared by the {@code pos} manifest; same as {@code SalesReadScope}). */
    public static final String STORE_SALES_PERMISSION = "STORE_SALES_VIEW";

    private final EntityManager em;

    public SaleDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String moduleId() {
        return "pos"; // Story 13.10a review: skipped by the pull while DISABLED
    }

    @Override
    public String entityKey() {
        return "sales";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        return queryDeltaPage(since, after, limit, PullScope.NONE); // fail closed: the pull always passes its scope
    }

    /**
     * Story 16.13 — a store member only pulls the sales of its assigned store; Story 19.14 — every seller of it only
     * with {@value #STORE_SALES_PERMISSION}, else his own sales. The items follow their sales.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Page queryDeltaPage(Instant since, Position after, int limit, PullScope scope) {
        // updated_at, not created_at: a validation/cancellation must reach the other devices.
        String columns = "id, store_id, employee_id, client_id, total_amount, " +
                "COALESCE(discount_amount, 0) AS discount_amount, payment_mode, " +
                "COALESCE(status, 'COMPLETED') AS status, occurred_at, created_at, mobile_money_ref";
        DeltaPageQuery.Rows page;
        if (scope != null && scope.restricted() && !scope.excludesEveryStore()
                && !scope.holds(STORE_SALES_PERMISSION)) {
            if (scope.actorId() == null) return new Page(List.of(), null); // fail closed: unknown actor
            page = DeltaPageQuery.fetch(em, columns, "sales", CURSOR, since, after, limit, OWN_SALES_SCOPE,
                    Map.of(DeltaPageQuery.SCOPE_STORE_PARAM, scope.storeId(),
                            DeltaPageQuery.SCOPE_ACTOR_PARAM, scope.actorId()));
        } else {
            page = DeltaPageQuery.fetchForStore(em, columns, "sales", CURSOR, since, after, limit, scope, STORE_SCOPE);
        }
        List<Object[]> saleRows = page.rows();

        if (saleRows.isEmpty()) return new Page(List.of(), page.next());

        // Build sale maps and collect IDs for batch item query
        List<String> saleIds = new ArrayList<>();
        Map<String, Map<String, Object>> saleMaps = new LinkedHashMap<>();
        for (Object[] row : saleRows) {
            Map<String, Object> map = mapSale(row);
            String saleId = (String) map.get("id");
            saleIds.add(saleId);
            map.put("items", new ArrayList<>());
            saleMaps.put(saleId, map);
        }

        // Batch-load all sale items in one query (eliminates N+1)
        String itemSql = "SELECT si.sale_id, si.id, si.product_id, si.variant_id, si.product_name, " +
                "si.applied_unit_price, COALESCE(si.catalogue_unit_price, si.applied_unit_price) AS catalogue_unit_price, " +
                "si.quantity, si.subtotal FROM sale_items si " +
                "WHERE si.sale_id IN (:saleIds)";
        Query itemQuery = em.createNativeQuery(itemSql);
        itemQuery.setParameter("saleIds", saleIds.stream()
                .map(java.util.UUID::fromString).toList());
        List<Object[]> itemRows = itemQuery.getResultList();

        for (Object[] itemRow : itemRows) {
            String parentSaleId = str(itemRow[0]);
            Map<String, Object> saleMap = saleMaps.get(parentSaleId);
            if (saleMap != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) saleMap.get("items");
                items.add(mapItem(itemRow));
            }
        }

        return new Page(new ArrayList<>(saleMaps.values()), page.next());
    }

    private Map<String, Object> mapSale(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("storeId", str(row[1]));
        map.put("employeeId", str(row[2]));
        map.put("clientId", str(row[3]));
        map.put("totalAmount", DeltaValues.num(row[4]));
        map.put("discountAmount", DeltaValues.num(row[5]));
        map.put("paymentMode", str(row[6]));
        map.put("status", str(row[7]));
        map.put("occurredAt", ts(row[8]));
        map.put("createdAt", ts(row[9]));
        // Without it the sale detail on another device / after a reinstall lost the reference.
        map.put("mobileMoneyRef", str(row[10]));
        return map;
    }

    private Map<String, Object> mapItem(Object[] row) {
        // row[0] = sale_id (used for grouping), item fields start at index 1
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[1]));
        map.put("productId", str(row[2]));
        map.put("variantId", str(row[3]));
        map.put("productName", str(row[4]));
        map.put("appliedUnitPrice", DeltaValues.num(row[5]));
        map.put("catalogueUnitPrice", DeltaValues.num(row[6]));
        map.put("quantity", DeltaValues.num(row[7]));
        map.put("subtotal", DeltaValues.num(row[8]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
