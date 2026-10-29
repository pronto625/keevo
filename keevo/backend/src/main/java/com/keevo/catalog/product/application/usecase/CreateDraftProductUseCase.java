package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.ProductCreatedProgressivelyEvent;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.event.catalog.product.DraftProductPendingEvent;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.subscription.api.ProductCountPort;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CreateDraftProductUseCase — Creates a product in DRAFT status.
 *
 * <p>Accessible to both OWNER and EMPLOYEE. OWNER may promote the draft via
 * {@link UpdateProductUseCase} (DRAFT → ACTIVE). EMPLOYEE creates a draft that
 * awaits owner validation.
 *
 * <p>Plan limit is checked against ACTIVE products only (drafts are staging, not billed).
 * If the plan is already at or above its active-product ceiling, creation is still allowed
 * as a DRAFT; the limit is enforced at promotion time.
 *
 * <p>Story 2.4 — AC5/AC6 (progressive creation by employees, owner review).
 */
@Service
public class CreateDraftProductUseCase {

    private static final String SKU_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final ProductRepository         productRepository;
    private final ProductCountPort          productCountPort;
    private final TenantPlanQuery           tenantPlanQuery;
    private final PlanLimitCheck            planLimit;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionLockPort       transactionLock;

    public CreateDraftProductUseCase(
            ProductRepository productRepository,
            ProductCountPort productCountPort,
            TenantPlanQuery tenantPlanQuery,
            PlanLimitCheck planLimit,
            ApplicationEventPublisher eventPublisher,
            TransactionLockPort transactionLock) {
        this.productRepository     = productRepository;
        this.productCountPort      = productCountPort;
        this.tenantPlanQuery       = tenantPlanQuery;
        this.planLimit             = planLimit;
        this.eventPublisher        = eventPublisher;
        this.transactionLock       = transactionLock;
    }

    public record CreateDraftCommand(
            UUID    clientId,    // optional: client-specified UUID; null → server generates
            String  name,
            String  description,
            UUID    categoryId,
            Integer price,
            Integer buyPrice,
            Integer transportCost,
            UUID    actorId,
            String  actorRole,   // "OWNER" or "EMPLOYEE"
            String  actorName
    ) {
        /** Backward-compatible constructor — server generates UUID. */
        public CreateDraftCommand(String name, String description, UUID categoryId,
                                  Integer price, Integer buyPrice, Integer transportCost,
                                  UUID actorId, String actorRole, String actorName) {
            this(null, name, description, categoryId, price, buyPrice, transportCost,
                 actorId, actorRole, actorName);
        }
    }

    /**
     * Execute DRAFT product creation.
     *
     * @param command draft creation command
     * @return the newly created DRAFT product
     */
    @Transactional
    public Product execute(CreateDraftCommand command) {

        // Story 13.9: same per-tenant advisory lock as CreateProductUseCase (shared key), first,
        // so idempotency lookup, name check and plan-limit count → save are serialised.
        transactionLock.lock("product-limit:" + TenantContext.getCurrentTenant());

        // Idempotency: if a client-specified UUID already exists, return the existing product.
        if (command.clientId() != null) {
            var existing = productRepository.findById(command.clientId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Name uniqueness (applies to both DRAFT and ACTIVE)
        if (productRepository.existsByName(command.name())) {
            throw new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS,
                    "Un produit avec le nom '" + command.name() + "' existe déjà");
        }

        // Plan limit check — all roles (DRAFT products count toward the tenant limit)
        int currentCount = productCountPort.countActiveProducts(TenantContext.getCurrentTenant());
        var plan = tenantPlanQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"));
        planLimit.checkProductLimit(plan, currentCount);

        String sku = generateSku();
        var now = Instant.now();

        var draft = new Product(
                command.clientId() != null ? command.clientId() : UUID.randomUUID(),
                command.name().trim(),
                command.description(),
                sku,
                command.categoryId(),
                command.price()         != null ? command.price()         : 0,
                command.buyPrice()      != null ? command.buyPrice()      : 0,
                command.transportCost() != null ? command.transportCost() : 0,
                0,    // stockQuantity — zero until promoted
                false,
                ProductStatus.DRAFT,
                0,    // minimumThreshold — set at promotion or via UpdateProductUseCase
                now,
                now
        );

        var saved = productRepository.save(draft);
        String tenantId = TenantContext.getCurrentTenant();

        // Track draft for badge counter (EMPLOYEE drafts need owner notification) — 12.6a: recorded by
        // messaging's synchronous listener, in this transaction
        eventPublisher.publishEvent(new DraftProductPendingEvent(
                saved.getId(), saved.getName(), command.actorId(), tenantId, Instant.now()));

        // Domain event → DraftProductNotificationListener will push notification
        eventPublisher.publishEvent(new ProductCreatedProgressivelyEvent(
                saved.getId(),
                saved.getName(),
                command.actorId(),
                command.actorName(),
                command.actorRole(),
                tenantId,
                "DRAFT",
                Instant.now()
        ));

        return saved;
    }

    private String generateSku() {
        StringBuilder sb = new StringBuilder("KEV-");
        for (int i = 0; i < 6; i++) {
            sb.append(SKU_CHARS.charAt(ThreadLocalRandom.current().nextInt(SKU_CHARS.length())));
        }
        return sb.toString();
    }
}
