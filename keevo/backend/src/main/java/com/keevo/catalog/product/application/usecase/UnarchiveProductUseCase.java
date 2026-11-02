package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.product.ProductUnarchivedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.api.ProductCountPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * UnarchiveProductUseCase — Business logic for restoring an archived product.
 *
 * <p>Story 17.10: archived products no longer count toward the plan's product limit, so restoring an archived
 * ACTIVE product passes the same limit check as a creation (same per-tenant lock, fail closed); restoring an
 * already-visible product or a draft is unchanged.
 */
@Service
public class UnarchiveProductUseCase {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductCountPort productCountPort;
    private final TenantPlanQuery tenantPlanQuery;
    private final PlanLimitCheck planLimit;
    private final TransactionLockPort transactionLock;

    public UnarchiveProductUseCase(ProductRepository productRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   ProductCountPort productCountPort,
                                   TenantPlanQuery tenantPlanQuery,
                                   PlanLimitCheck planLimit,
                                   TransactionLockPort transactionLock) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
        this.productCountPort = productCountPort;
        this.tenantPlanQuery = tenantPlanQuery;
        this.planLimit = planLimit;
        this.transactionLock = transactionLock;
    }

    public record UnarchiveProductDto(
        UUID productId,
        UUID actorId
    ) {}

    /**
     * Restores the product. Story 17.13: a product that is no longer archived (e.g. restored concurrently between the
     * caller's lookup and this call) is a no-op — no event, no audit — and answers {@code false}.
     *
     * @return {@code true} when the product was actually restored
     * @throws DomainException PRODUCT_NOT_FOUND; PLAN_LIMIT_EXCEEDED (archived ACTIVE product at the plan limit)
     */
    @Transactional
    public boolean execute(UnarchiveProductDto dto) {
        return restore(dto, false);
    }

    /**
     * Story 17.13 — same restore, but an archived ACTIVE product at the plan limit is left archived and answers
     * {@code false} instead of throwing, so a caller sharing the transaction (sync draft creation) can fall back
     * without the transaction being marked rollback-only. An unreadable count still fails closed (throws).
     */
    @Transactional
    public boolean tryExecute(UnarchiveProductDto dto) {
        return restore(dto, true);
    }

    private boolean restore(UnarchiveProductDto dto, boolean refuseQuietly) {
        String tenantId = TenantContext.getCurrentTenant();
        // same key as product creation: the count → restore cannot interleave with a concurrent create
        transactionLock.lock("product-limit:" + tenantId);

        var product = productRepository.findById(dto.productId())
            .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Produit introuvable"));

        if (!Boolean.TRUE.equals(product.getArchived())) {
            return false;
        }

        if (product.getStatus() == ProductStatus.ACTIVE) {
            var plan = tenantPlanQuery.findCurrentPlan(tenantId)
                    .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                            "Aucun abonnement actif"));
            if (refuseQuietly) {
                if (planLimit.remainingProducts(plan, () -> productCountPort.countActiveProducts(tenantId)) <= 0) {
                    return false;
                }
            } else {
                planLimit.checkProductLimit(plan, () -> productCountPort.countActiveProducts(tenantId));
            }
        }

        productRepository.unarchive(dto.productId());

        eventPublisher.publishEvent(new ProductUnarchivedEvent(
            product.getId(),
            product.getName(),
            product.getSku(),
            tenantId,
            dto.actorId(),
            Instant.now()
        ));
        return true;
    }
}
