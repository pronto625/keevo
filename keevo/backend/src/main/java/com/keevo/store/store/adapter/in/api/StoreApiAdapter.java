package com.keevo.store.store.adapter.in.api;

import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.CreateStoreWithIdInput;
import com.keevo.store.api.StoreCommandPort;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.UpdateStoreInput;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreFactory;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.api.PlanLimitPort;
import com.keevo.subscription.api.SubscriptionQueryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the published store ports (Story 12.2d) over the {@link StoreRepository} port and
 * the store use cases. No transaction of its own: every call joins the caller's transaction
 * (the store use cases are {@code @Transactional} REQUIRED, so they join it too).
 *
 * <p>Tenant convention: the repository reads the current tenant context, so the explicit
 * {@code tenantSchema} must be that schema; a mismatch fails fast ({@link IllegalStateException}).
 */
@Component
class StoreApiAdapter implements StoreQueryPort, StoreCommandPort {

    private final StoreRepository storeRepository;
    private final UpdateStoreUseCase updateStoreUseCase;
    private final DeactivateStoreUseCase deactivateStoreUseCase;
    private final SubscriptionQueryPort subscriptionQuery;
    private final PlanLimitPort planLimit;
    private final TransactionLockPort transactionLock;

    StoreApiAdapter(StoreRepository storeRepository,
                    UpdateStoreUseCase updateStoreUseCase,
                    DeactivateStoreUseCase deactivateStoreUseCase,
                    SubscriptionQueryPort subscriptionQuery,
                    PlanLimitPort planLimit,
                    TransactionLockPort transactionLock) {
        this.storeRepository = storeRepository;
        this.updateStoreUseCase = updateStoreUseCase;
        this.deactivateStoreUseCase = deactivateStoreUseCase;
        this.subscriptionQuery = subscriptionQuery;
        this.planLimit = planLimit;
        this.transactionLock = transactionLock;
    }

    // ── StoreQueryPort ────────────────────────────────────────────────────────

    @Override
    public Optional<StoreView> findById(String tenantSchema, UUID storeId) {
        TenantContext.requireCurrent(tenantSchema);
        return storeRepository.findById(storeId).map(StoreViews::of);
    }

    @Override
    public List<StoreView> findAllActive(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return storeRepository.findAllActive().stream().map(StoreViews::of).toList();
    }

    @Override
    public int countActive(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return storeRepository.countActive();
    }

    // ── StoreCommandPort ──────────────────────────────────────────────────────

    @Override
    public String validateName(String name) {
        return StoreFactory.validateName(name);
    }

    /**
     * Same lock key as {@code CreateStoreService}, then idempotency, plan limit, warehouse
     * uniqueness and insert — the order the CREATE_STORE sync handler used (v1s-13-13, v1s-16-6).
     */
    @Override
    public boolean createWithId(String tenantSchema, CreateStoreWithIdInput in) {
        TenantContext.requireCurrent(tenantSchema);
        // v1s-13-13: per-tenant lock BEFORE the idempotency read, so a concurrent replay of the same id
        // sees the winner's row instead of a spurious plan-limit refusal (held until commit).
        transactionLock.lock("store-limit:" + tenantSchema);
        if (storeRepository.findById(in.storeId()).isPresent()) {
            return false;
        }
        StoreType type = in.type() != null ? in.type() : StoreType.STORE;

        var plan = subscriptionQuery.findCurrentPlan(tenantSchema)
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"));
        planLimit.checkStoreLimit(plan, storeRepository.countActive());

        if (type == StoreType.WAREHOUSE && storeRepository.existsWarehouse()) {
            throw new DomainException(ErrorCode.WAREHOUSE_ALREADY_EXISTS,
                    "Vous avez déjà un entrepôt. Un seul entrepôt est autorisé par compte.");
        }

        Instant now = Instant.now();
        storeRepository.save(new Store(in.storeId(), StoreFactory.validateName(in.name()), type,
                in.address(), in.phone(), true, now, now));
        return true;
    }

    @Override
    public StoreView update(String tenantSchema, UpdateStoreInput in) {
        TenantContext.requireCurrent(tenantSchema);
        return StoreViews.of(updateStoreUseCase.execute(new UpdateStoreCommand(
                in.storeId(), in.name(), in.address(), in.phone(), in.actorId())));
    }

    @Override
    public void deactivate(String tenantSchema, UUID storeId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        deactivateStoreUseCase.execute(new DeactivateStoreCommand(storeId, actorId));
    }

}
