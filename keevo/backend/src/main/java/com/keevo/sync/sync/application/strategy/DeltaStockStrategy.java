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

    public DeltaStockStrategy(EntityManager entityManager, ApplicationEventPublisher eventPublisher) {
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
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
        return new ConflictResult(null, "DELTA_SUM", null);
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
