package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.ProductCreatedProgressivelyEvent;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private final DraftNotificationRepository draftRepository;
    private final ProductCountPort          productCountPort;
    private final SubscriptionRepository    subscriptionRepository;
    private final PlanLimitGuard            planLimitGuard;
    private final ApplicationEventPublisher eventPublisher;

    public CreateDraftProductUseCase(
            ProductRepository productRepository,
            DraftNotificationRepository draftRepository,
            ProductCountPort productCountPort,
            SubscriptionRepository subscriptionRepository,
            PlanLimitGuard planLimitGuard,
            ApplicationEventPublisher eventPublisher) {
        this.productRepository     = productRepository;
        this.draftRepository       = draftRepository;
        this.productCountPort      = productCountPort;
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitGuard        = planLimitGuard;
        this.eventPublisher        = eventPublisher;
    }

    public record CreateDraftCommand(
            String  name,
            String  description,
            UUID    categoryId,
            Integer price,
            Integer buyPrice,
            Integer transportCost,
            UUID    actorId,
            String  actorRole,   // "OWNER" or "EMPLOYEE"
            String  actorName
    ) {}

    /**
     * Execute DRAFT product creation.
     *
     * @param command draft creation command
     * @return the newly created DRAFT product
     */
    @Transactional
    public Product execute(CreateDraftCommand command) {

        // Name uniqueness (applies to both DRAFT and ACTIVE)
        if (productRepository.existsByName(command.name())) {
            throw new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS,
                    "Un produit avec le nom '" + command.name() + "' existe déjà");
        }

        // Plan limit check — all roles (DRAFT products count toward the tenant limit)
        int currentCount = productCountPort.countActiveProducts();
        var planType = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"))
                .getPlanType();
        planLimitGuard.checkProductLimit(planType, currentCount);

        String sku = generateSku();
        var now = Instant.now();

        var draft = new Product(
                UUID.randomUUID(),
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

        // Derive a deterministic UUID from the tenant schema name so the tracking record
        // carries a meaningful tenant identifier instead of a nil UUID.
        UUID tenantUuid = UUID.nameUUIDFromBytes(tenantId.getBytes(StandardCharsets.UTF_8));

        // Track draft for badge counter (EMPLOYEE drafts need owner notification)
        var pending = DraftPendingValidation.create(
                saved.getId(), saved.getName(), command.actorId(),
                tenantUuid);
        draftRepository.save(pending);

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
