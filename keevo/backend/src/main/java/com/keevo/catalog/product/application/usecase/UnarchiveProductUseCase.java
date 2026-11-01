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

    @Transactional
    public void execute(UnarchiveProductDto dto) {
        String tenantId = TenantContext.getCurrentTenant();
        // same key as product creation: the count → restore cannot interleave with a concurrent create
        transactionLock.lock("product-limit:" + tenantId);

        var product = productRepository.findById(dto.productId())
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + dto.productId()));

        if (Boolean.TRUE.equals(product.getArchived()) && product.getStatus() == ProductStatus.ACTIVE) {
            var plan = tenantPlanQuery.findCurrentPlan(tenantId)
                    .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                            "Aucun abonnement actif"));
            planLimit.checkProductLimit(plan, () -> productCountPort.countActiveProducts(tenantId));
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
    }
}
