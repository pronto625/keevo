package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CreateStoreSyncHandler — persists a store with the UUID generated on-device (Story 16.6).
 *
 * <p>Idempotent: if a store with the same UUID already exists the operation is
 * returned as APPLIED without creating a duplicate.
 *
 * <p>Bypasses {@code CreateStoreUseCase} intentionally — the use case generates
 * its own {@code UUID.randomUUID()}, which would discard the client-assigned UUID
 * needed for offline-first idempotency. Plan-limit and warehouse-uniqueness checks
 * are re-enforced here directly (mirroring {@code CreateStoreService}) — code review
 * of v1s-16-6 found that, once this handler exists, a queued CREATE_STORE reaching
 * the backend without these checks would let an offline OWNER exceed the FREE-plan
 * store cap or create a second warehouse.
 */
@Component
public class CreateStoreSyncHandler extends AbstractSyncOperationHandler {

    private final StoreRepository storeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanLimitGuard planLimitGuard;

    public CreateStoreSyncHandler(StoreRepository storeRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   PlanLimitGuard planLimitGuard) {
        this.storeRepository = storeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitGuard = planLimitGuard;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_STORE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("id") == null)
            throw new IllegalArgumentException("Missing required field: id");
        if (p.get("name") == null || ((String) p.get("name")).isBlank())
            throw new IllegalArgumentException("Missing required field: name");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        // Story v1s-12-8 / v1s-16-6 AC8: OWNER-only guard (mirror StoreController.requireOwner())
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can manage stores");
        }

        Map<String, Object> p = operation.payload();
        UUID storeId = UUID.fromString((String) p.get("id"));

        // Idempotency: if already synced, return APPLIED without duplicate insert.
        if (storeRepository.findById(storeId).isPresent()) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                    storeId.toString(), null);
        }

        StoreType type = p.get("type") != null
                ? StoreType.valueOf((String) p.get("type"))
                : StoreType.STORE;

        // Plan-limit and warehouse-uniqueness checks — mirrors CreateStoreService.
        var planType = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"))
                .getPlanType();
        planLimitGuard.checkStoreLimit(planType, storeRepository.countActive());

        if (type == StoreType.WAREHOUSE && storeRepository.existsWarehouse()) {
            throw new DomainException(ErrorCode.WAREHOUSE_ALREADY_EXISTS,
                    "Vous avez déjà un warehouse. Un seul warehouse est autorisé par compte.");
        }

        Instant now = Instant.now();
        Store store = new Store(
                storeId,
                (String) p.get("name"),
                type,
                (String) p.getOrDefault("address", null),
                (String) p.getOrDefault("phone", null),
                true,
                now,
                now
        );
        storeRepository.save(store);
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                storeId.toString(), null);
    }
}
