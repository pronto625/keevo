package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ConflictStrategyRegistry — Factory Method mapping operation types to conflict strategies.
 */
@Component
public class ConflictStrategyRegistry {

    private static final Set<String> DELTA_STOCK_OPS = Set.of(
            "CREATE_SALE", "STOCK_ADJUST", "RECORD_STOCK_ENTRY", "STOCK_TRANSFER");

    private static final Set<String> LWW_OPS = Set.of(
            "CREATE_PRODUCT", "PRODUCT_CREATE", "PRODUCT_UPDATE", "UPDATE_PRODUCT",
            "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT",
            "UPDATE_CLIENT", "ARCHIVE_CLIENT",
            "UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER",
            "STORE_UPDATE",
            "CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE",
            "DEACTIVATE_EMPLOYEE", "REACTIVATE_EMPLOYEE");

    private final Map<String, ConflictResolutionStrategy> strategies;
    private final AppendOnlyStrategy fallback;

    public ConflictStrategyRegistry(DeltaStockStrategy deltaStock,
                                    LastWriteWinsStrategy lww,
                                    AppendOnlyStrategy appendOnly) {
        this.fallback = appendOnly;
        this.strategies = new HashMap<>();
        DELTA_STOCK_OPS.forEach(op -> strategies.put(op, deltaStock));
        LWW_OPS.forEach(op -> strategies.put(op, lww));
    }

    public ConflictResolutionStrategy resolve(String operationType) {
        return strategies.getOrDefault(operationType, fallback);
    }
}
