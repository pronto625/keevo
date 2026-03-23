package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.model.ConflictResult;
import com.keevo.sync.sync.domain.model.StockConflictResolvedEvent;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * DeltaStockStrategy — Checks post-application stock level for negative values.
 * Stock operations are delta-based: both deltas are applied sequentially.
 * This strategy only flags when the resulting stock goes negative.
 */
@Component
public class DeltaStockStrategy implements ConflictResolutionStrategy {

    /** Valid tenant schema pattern — prevents SQL injection in native queries. */
    private static final Pattern TENANT_PATTERN = Pattern.compile("^kv_[a-z0-9]{6}$");

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
        if (tenantId == null || !TENANT_PATTERN.matcher(tenantId).matches()) return null;

        Map<String, Object> payload = operation.payload();
        String productId = extractProductId(payload, operation.operationType());
        String storeId = extractStoreId(payload, operation.operationType());
        if (productId == null || storeId == null) return null;

        Integer currentStock = queryCurrentStock(tenantId, productId, storeId);
        if (currentStock == null) return new ConflictResult(null, "DELTA_SUM", null);

        int deltaApplied = extractDelta(payload, operation.operationType());
        int previousStock = currentStock - deltaApplied;

        // Publish StockConflictResolvedEvent always (AC12 — audit completeness)
        eventPublisher.publishEvent(new StockConflictResolvedEvent(
                operation.operationId(), productId, storeId,
                deltaApplied, currentStock, previousStock,
                "DELTA_SUM", currentStock < 0, tenantId, Instant.now()));

        if (currentStock < 0) {
            String productName = resolveProductName(tenantId, productId);
            Map<String, Object> conflictData = new LinkedHashMap<>();
            conflictData.put("conflictType", "STOCK_NEGATIVE");
            conflictData.put("productId", productId);
            conflictData.put("productName", productName);
            conflictData.put("storeId", storeId);
            conflictData.put("deltaApplied", deltaApplied);
            conflictData.put("resultingStock", currentStock);
            conflictData.put("previousStock", previousStock);
            return new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", conflictData);
        }

        return new ConflictResult(null, "DELTA_SUM", null);
    }

    @SuppressWarnings("unchecked")
    private String extractProductId(Map<String, Object> payload, String opType) {
        if ("CREATE_SALE".equals(opType)) {
            var items = (List<Map<String, Object>>) payload.get("items");
            if (items != null && !items.isEmpty()) {
                return String.valueOf(items.get(0).get("productId"));
            }
            return null;
        }
        Object pid = payload.get("productId");
        return pid != null ? String.valueOf(pid) : null;
    }

    private String extractStoreId(Map<String, Object> payload, String opType) {
        Object sid = payload.get("storeId");
        return sid != null ? String.valueOf(sid) : null;
    }

    @SuppressWarnings("unchecked")
    private int extractDelta(Map<String, Object> payload, String opType) {
        return switch (opType) {
            case "CREATE_SALE" -> {
                var items = (List<Map<String, Object>>) payload.get("items");
                if (items == null) yield 0;
                int total = 0;
                for (var item : items) {
                    Object qty = item.get("quantity");
                    total += (qty instanceof Number n) ? n.intValue() : 0;
                }
                yield -total;
            }
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

    private Integer queryCurrentStock(String tenantId, String productId, String storeId) {
        try {
            Object result = entityManager.createNativeQuery(
                            "SELECT quantity FROM \"" + tenantId + "\".stock_levels WHERE product_id = CAST(?1 AS uuid) AND store_id = CAST(?2 AS uuid)")
                    .setParameter(1, productId)
                    .setParameter(2, storeId)
                    .getSingleResult();
            return ((Number) result).intValue();
        } catch (NoResultException e) {
            return null;
        }
    }

    private String resolveProductName(String tenantId, String productId) {
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
