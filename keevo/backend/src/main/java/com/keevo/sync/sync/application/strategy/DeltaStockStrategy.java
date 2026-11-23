package com.keevo.sync.sync.application.strategy;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.sync.sync.domain.model.ConflictResult;
import com.keevo.sync.sync.domain.model.StockConflictResolvedEvent;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import com.keevo.inventory.api.StockFloor;
import com.keevo.inventory.api.StockQueryPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * DeltaStockStrategy — Checks post-application stock level for negative values.
 * Stock operations are delta-based: both deltas are applied sequentially.
 * This strategy only flags when the resulting stock goes negative.
 */
@Component
public class DeltaStockStrategy implements ConflictResolutionStrategy {

    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;
    private final StockQueryPort stockQuery; // Story 17.12 — untracked products are never a stock conflict

    public DeltaStockStrategy(EntityManager entityManager, ApplicationEventPublisher eventPublisher,
                              StockQueryPort stockQuery) {
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.stockQuery = stockQuery;
    }

    @Override
    public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                   UUID actorId, String tenantId) {
        if (handlerResult.status() != SyncOperationStatus.APPLIED) return null;
        if (!TenantSchema.isValid(tenantId)) return null; // prevents SQL injection in native queries

        Map<String, Object> payload = operation.payload();
        String storeId = extractStoreId(payload, operation.operationType());
        List<StockTouch> touches = extractTouches(payload, operation.operationType());
        if (touches.isEmpty() || storeId == null) return null;

        // Every product of a multi-item sale is checked; the first one that went
        // negative is reported (one conflict per operation).
        ConflictResult negative = null;
        for (StockTouch touch : touches) {
            // Story 17.12: an untracked product was not moved — its frozen level (even negative) is no conflict
            if (isUntracked(tenantId, touch.productId())) continue;
            Integer currentStock = queryCurrentStock(tenantId, touch.productId(), touch.variantId(), storeId);
            if (currentStock == null) continue;

            int deltaApplied = touch.delta();
            int previousStock = currentStock - deltaApplied;
            // Only an operation that took stock out can have caused negative stock: an entry
            // on stock that is already negative (late offline sales) is not a conflict.
            boolean wentNegative = deltaApplied < 0 && currentStock < 0;

            // Publish StockConflictResolvedEvent always (AC12 — audit completeness)
            eventPublisher.publishEvent(new StockConflictResolvedEvent(
                    operation.operationId(), touch.productId(), storeId,
                    deltaApplied, currentStock, previousStock,
                    "DELTA_SUM", wentNegative, tenantId, Instant.now()));

            if (wentNegative && negative == null) {
                String productName = resolveProductName(tenantId, touch.productId());
                Map<String, Object> conflictData = new LinkedHashMap<>();
                conflictData.put("conflictType", "STOCK_NEGATIVE");
                conflictData.put("productId", touch.productId());
                conflictData.put("productName", productName);
                conflictData.put("storeId", storeId);
                conflictData.put("deltaApplied", deltaApplied);
                conflictData.put("resultingStock", currentStock);
                conflictData.put("previousStock", previousStock);
                negative = new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", conflictData);
            }
        }

        if (negative != null) return negative;
        ConflictResult floored = "CREATE_SALE".equals(operation.operationType())
                ? flooredSale(tenantId, payload, storeId) : null;
        if (floored != null) return floored;
        return new ConflictResult(null, "DELTA_SUM", null);
    }

    /** Story 22.19 (D11) — conflict type of a synchronised sale whose store stock was floored at 0. */
    public static final String STOCK_FLOORED = "STOCK_FLOORED";

    /**
     * Story 22.19 (D11) — the sale lines the inventory floored ({@link StockFloor}: an automatic adjustment whose id is
     * derived from the line's movement id = its {@code itemId}): one query for every line; the operation stays APPLIED
     * and carries a French {@code warning}. Null when no line was floored (or the lines carry no id).
     */
    @SuppressWarnings("unchecked")
    private ConflictResult flooredSale(String tenantId, Map<String, Object> payload, String storeId) {
        var items = (List<Map<String, Object>>) payload.get("items");
        if (items == null) return null;
        Map<String, Integer> quantityOfAdjustment = new LinkedHashMap<>();
        for (var item : items) {
            Object itemId = item.get("itemId");
            if (itemId == null || item.get("productId") == null) continue;
            try {
                Object qty = item.get("quantity");
                quantityOfAdjustment.put(StockFloor.adjustmentIdOf(UUID.fromString(String.valueOf(itemId))).toString(),
                        qty instanceof Number n ? n.intValue() : Integer.MAX_VALUE);
            } catch (IllegalArgumentException e) {
                // not a UUID: never a movement id
            }
        }
        if (quantityOfAdjustment.isEmpty() || !TenantSchema.isValid(tenantId)) return null;
        // one statement: the adjustments of these lines with their product's name
        List<Object[]> rows = entityManager.createNativeQuery("SELECT CAST(m.id AS text), CAST(m.product_id AS text), "
                        + "m.quantity_change, p.name FROM \"" + tenantId + "\".stock_movements m LEFT JOIN \""
                        + tenantId + "\".products p ON p.id = m.product_id WHERE m.id = ANY (CAST(?1 AS uuid[]))")
                .setParameter(1, "{" + String.join(",", quantityOfAdjustment.keySet()) + "}")
                .getResultList();
        if (rows.isEmpty()) return null;
        List<Map<String, Object>> lines = new ArrayList<>();
        StringBuilder warning = new StringBuilder("Stock insuffisant à la synchronisation — manquant : ");
        for (Object[] row : rows) {
            // the adjustment also absorbs a level that was already negative: never report more than the line sold
            int missing = Math.min(((Number) row[2]).intValue(), quantityOfAdjustment.get((String) row[0]));
            String name = row[3] == null ? "Produit" : String.valueOf(row[3]);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("productId", row[1]);
            line.put("productName", name);
            line.put("missingQuantity", missing);
            lines.add(line);
            warning.append(lines.size() > 1 ? ", " : "").append(missing).append(" × ").append(name);
        }
        warning.append(". Le stock de la boutique a été remis à 0.");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conflictType", STOCK_FLOORED);
        data.put("storeId", storeId);
        data.put("lines", lines);
        data.put("warning", warning.toString());
        return new ConflictResult(STOCK_FLOORED, "DELTA_SUM", data);
    }

    private boolean isUntracked(String tenantId, String productId) {
        try {
            return stockQuery.isUntracked(tenantId, UUID.fromString(productId));
        } catch (IllegalArgumentException e) {
            return false; // not a UUID: the usual (null-stock) path applies
        }
    }

    /** One (product, variant) stock level touched by the operation, with its summed delta. */
    private record StockTouch(String productId, String variantId, int delta) {}

    @SuppressWarnings("unchecked")
    private List<StockTouch> extractTouches(Map<String, Object> payload, String opType) {
        if ("CREATE_SALE".equals(opType)) {
            var items = (List<Map<String, Object>>) payload.get("items");
            if (items == null) return List.of();
            Map<List<String>, Integer> deltas = new LinkedHashMap<>();
            for (var item : items) {
                Object pid = item.get("productId");
                if (pid == null) continue;
                Object vid = item.get("variantId");
                Object qty = item.get("quantity");
                int quantity = (qty instanceof Number n) ? n.intValue() : 0;
                deltas.merge(Arrays.asList(String.valueOf(pid), vid != null ? String.valueOf(vid) : null),
                        -quantity, Integer::sum);
            }
            List<StockTouch> touches = new ArrayList<>();
            deltas.forEach((key, delta) -> touches.add(new StockTouch(key.get(0), key.get(1), delta)));
            return touches;
        }
        Object pid = payload.get("productId");
        if (pid == null) return List.of();
        Object vid = payload.get("variantId");
        return List.of(new StockTouch(String.valueOf(pid), vid != null ? String.valueOf(vid) : null,
                extractDelta(payload, opType)));
    }

    private String extractStoreId(Map<String, Object> payload, String opType) {
        Object sid = payload.get("storeId");
        return sid != null ? String.valueOf(sid) : null;
    }

    private int extractDelta(Map<String, Object> payload, String opType) {
        return switch (opType) {
            case "STOCK_ADJUST" -> {
                Object qty = payload.get("quantity");
                yield (qty instanceof Number n) ? n.intValue() : 0;
            }
            case "RECORD_STOCK_ENTRY" -> {
                Object qty = payload.get("quantity");
                yield (qty instanceof Number n) ? n.intValue() : 0;
            }
            case "STOCK_TRANSFER" -> {
                Object qty = payload.get("quantity");
                yield (qty instanceof Number n) ? -n.intValue() : 0;
            }
            default -> 0;
        };
    }

    private Integer queryCurrentStock(String tenantId, String productId, String variantId, String storeId) {
        if (!TenantSchema.isValid(tenantId)) return null; // no conflict evaluated, same as evaluate()
        try {
            String variantClause = variantId != null
                    ? " AND variant_id = CAST(?3 AS uuid)"
                    : " AND variant_id IS NULL";
            var query = entityManager.createNativeQuery(
                            "SELECT quantity FROM \"" + tenantId + "\".stock_levels WHERE product_id = CAST(?1 AS uuid) AND store_id = CAST(?2 AS uuid)"
                                    + variantClause)
                    .setParameter(1, productId)
                    .setParameter(2, storeId);
            if (variantId != null) query.setParameter(3, variantId);
            Object result = query.getSingleResult();
            return ((Number) result).intValue();
        } catch (NoResultException e) {
            return null;
        }
    }

    private String resolveProductName(String tenantId, String productId) {
        if (!TenantSchema.isValid(tenantId)) return "Unknown"; // same fallback as a failed lookup
        try {
            Object result = entityManager.createNativeQuery(
                            "SELECT name FROM \"" + tenantId + "\".products WHERE id = CAST(?1 AS uuid)")
                    .setParameter(1, productId)
                    .getSingleResult();
            return String.valueOf(result);
        } catch (Exception e) {
            return "Unknown";
        }
    }
}
