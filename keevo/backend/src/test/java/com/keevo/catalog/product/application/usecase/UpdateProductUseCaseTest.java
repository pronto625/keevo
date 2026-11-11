package com.keevo.catalog.product.application.usecase;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.time.Instant;
import java.util.Optional;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.event.catalog.product.DraftProductValidatedEvent;
import com.keevo.shared.event.catalog.product.ProductPriceChangedEvent;
import com.keevo.shared.domain.exception.ErrorCode;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TDD tests for UpdateProductUseCase — covers basic update + Story 2.4 DRAFT promotion.
 */
@ExtendWith(MockitoExtension.class)
class UpdateProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;
    
    @Mock
    private ObjectMapper objectMapper;

    @Mock private com.keevo.subscription.api.ProductCountPort productCountPort;
    @Mock private com.keevo.shared.capability.entitlement.TenantPlanQuery tenantPlanQuery;
    @Mock private com.keevo.shared.capability.entitlement.PlanLimitCheck planLimit;
    @Mock private com.keevo.shared.application.port.TransactionLockPort transactionLock;

    private final com.keevo.shared.capability.entitlement.PlanView free =
            new com.keevo.shared.capability.entitlement.PlanView(com.keevo.identity.api.PlanType.FREE,
                    com.keevo.identity.api.PlanKind.FREE, true, null, 1, 500, 2);

    private UpdateProductUseCase useCase;
    @Mock private SetProductStockTrackingUseCase stockTracking; // Story 17.12

    @BeforeEach
    void setUp() {
        useCase = new UpdateProductUseCase(productRepository, eventPublisher, objectMapper, productCountPort,
                tenantPlanQuery, planLimit, transactionLock, stockTracking);
        lenient().when(tenantPlanQuery.findCurrentPlan(any())).thenReturn(Optional.of(free));
    }

    // ── Story 17.10: promotion passes the product limit ─────────────────────────

    private Product draft(UUID id, boolean archived) {
        return new Product(id, "Savon", null, "KEV-SAVON1", null,
                500, 0, 0, 0, archived, ProductStatus.DRAFT, 0, Instant.now(), Instant.now());
    }

    @Test
    void promotion_at_the_limit_is_refused_and_nothing_is_saved() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(draft(id, false)));
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "products limit reached"))
                .when(planLimit).checkProductLimit(eq(free), any());

        var ex = assertThrows(DomainException.class, () -> useCase.execute(new UpdateProductUseCase.UpdateProductDto(
                id, null, null, null, null, null, null, null, null, UUID.randomUUID(), "OWNER")));

        assertEquals(ErrorCode.PLAN_LIMIT_EXCEEDED, ex.getErrorCode());
        var order = inOrder(transactionLock, planLimit);
        order.verify(transactionLock).lock(startsWith("product-limit:"));
        order.verify(planLimit).checkProductLimit(eq(free), any());
        verify(productRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void active_update_or_archived_draft_promotion_does_not_check_the_limit() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(draft(id, true)));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TenantContext.setCurrentTenant("kv_test01");
        try {
            useCase.execute(new UpdateProductUseCase.UpdateProductDto(
                    id, null, null, null, null, 700, null, null, null, UUID.randomUUID(), "OWNER"));
        } finally {
            TenantContext.clear();
        }

        verifyNoInteractions(planLimit, transactionLock);
    }

    // Device test: a price-only PATCH answered 404 « Produit introuvable ».
    @Test
    void price_only_patch_keeps_the_name_and_a_blank_name_is_a_validation_error() {
        var productId = UUID.randomUUID();
        var existing = new Product(productId, "Riz 5kg", null, "KEV-RIZ5KG", null,
                3500, 2800, 0, null, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var priceOnly = new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                3700, null, null, null, UUID.randomUUID(), "OWNER");
        var result = useCase.execute(priceOnly);
        assertEquals("Riz 5kg", result.getName());
        assertEquals("3700 XAF", String.valueOf(result.getPrice()));

        var blank = new UpdateProductUseCase.UpdateProductDto(productId, "  ", null, null, null,
                3700, null, null, null, UUID.randomUUID(), "OWNER");
        DomainException ex = assertThrows(DomainException.class, () -> useCase.execute(blank));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    // Story 17.12 — the setting survives a plain edit; a change goes through the tracking use case
    @Test
    void trackStock_keptWhenAbsent() {
        var productId = UUID.randomUUID();
        var untracked = new Product(productId, "Livraison", null, "KEV-LIVR01", null,
                500, 0, 0, 7, false, ProductStatus.ACTIVE, 0, false, Instant.now(), Instant.now());
        when(productRepository.findById(productId)).thenReturn(Optional.of(untracked));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var kept = useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                700, null, null, 9, UUID.randomUUID(), "EMPLOYEE"));

        assertFalse(kept.isTrackStock());
        assertEquals(0, kept.getStockQuantity()); // legacy column never written for an untracked product
        verifyNoInteractions(stockTracking);
    }

    @Test
    void trackStockOnly_goesThroughTheTrackingUseCase_noProductUpdatedEntry() {
        var productId = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var tracked = new Product(productId, "Huile", "Bidon", "KEV-HUIL01", null,
                500, 0, 0, null, false, ProductStatus.DRAFT, Instant.now(), Instant.now());
        when(productRepository.findById(productId)).thenReturn(Optional.of(tracked));

        useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                null, null, null, null, actor, "OWNER", false));

        verify(stockTracking).execute(productId, false, actor, "OWNER");
        verify(productRepository, never()).save(any());
        verifyNoInteractions(eventPublisher); // no « Produit modifié », no draft promotion
    }

    @Test
    void trackStockSameValueOnly_isANoOp() {
        var productId = UUID.randomUUID();
        var tracked = new Product(productId, "Huile", null, "KEV-HUIL01", null,
                500, 0, 0, null, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findById(productId)).thenReturn(Optional.of(tracked));

        useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                null, null, null, null, UUID.randomUUID(), "EMPLOYEE", true));

        verifyNoInteractions(stockTracking, eventPublisher);
        verify(productRepository, never()).save(any());
    }

    @Test
    void trackStockWithOtherChanges_trackingFirst_thenOneProductUpdatedEntry() {
        var productId = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var tracked = new Product(productId, "Huile", null, "KEV-HUIL01", null,
                500, 0, 0, null, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        var untracked = new Product(productId, "Huile", null, "KEV-HUIL01", null,
                500, 0, 0, null, false, ProductStatus.ACTIVE, 0, false, Instant.now(), Instant.now());
        when(productRepository.findById(productId)).thenReturn(Optional.of(tracked), Optional.of(untracked));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                900, null, null, null, actor, "OWNER", false));

        verify(stockTracking).execute(productId, false, actor, "OWNER");
        assertFalse(saved.isTrackStock());
        assertEquals(900, saved.getPriceValue());
        verify(eventPublisher).publishEvent(isA(com.keevo.shared.event.catalog.product.ProductUpdatedEvent.class));
    }

    @Test
    void should_update_existing_product() {
        // Given
        var productId = UUID.randomUUID();
        var existingProduct = new Product(
            productId,
            "Old Name",
            "Old Description",
            "KEV-OLD123",
            UUID.randomUUID(),
            null, // price
            null, // buyPrice
            null, // transportCost
            null, // stockQuantity
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );

        var dto = new UpdateProductUseCase.UpdateProductDto(
            productId,
            "New Name",
            "New Description", 
            "KEV-NEW123",
            UUID.randomUUID(),
            null, // price
            null, // buyPrice
            null, // transportCost
            null, // stockQuantity
            UUID.randomUUID(), // actorId
            "OWNER"
        );

        when(productRepository.findById(productId)).thenReturn(Optional.of(existingProduct));
        when(productRepository.findBySku("KEV-NEW123")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        var result = useCase.execute(dto);

        // Then
        assertEquals("New Name", result.getName());
        assertEquals("KEV-NEW123", result.getSku());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void should_throw_exception_when_product_not_found() {
        // Given
        var productId = UUID.randomUUID();
        var dto = new UpdateProductUseCase.UpdateProductDto(
            productId,
            "Name",
            "Description",
            "KEV-ABC123",
            UUID.randomUUID(),
            null, // price
            null, // buyPrice
            null, // transportCost
            null, // stockQuantity
            UUID.randomUUID(), // actorId
            "OWNER"
        );

        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When/Then
        DomainException ex = assertThrows(DomainException.class, () -> useCase.execute(dto));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
    }

    // ── Story 2.4: DRAFT → ACTIVE promotion ──────────────────────────────────

    @Test
    void shouldPromoteDraftToActiveWhenOwnerUpdatesWithAllRequiredFields() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");

            var productId = UUID.randomUUID();
            var categoryId = UUID.randomUUID();
            var draftProduct = new Product(
                    productId, "Produit", null, "KEV-DRF001", categoryId,
                    5000, 0, 0, 0, false, ProductStatus.DRAFT, 0,
                    Instant.now(), Instant.now());

            var dto = new UpdateProductUseCase.UpdateProductDto(
                    productId, "Produit", null, "KEV-DRF001", categoryId,
                    5000, null, null, null, UUID.randomUUID(), "OWNER");

            when(productRepository.findById(productId)).thenReturn(Optional.of(draftProduct));
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var result = useCase.execute(dto);

            assertEquals(ProductStatus.ACTIVE, result.getStatus(),
                    "DRAFT doit être promu ACTIVE quand l'OWNER soumet tous les champs requis");
        }
    }

    @Test
    void shouldThrowForbiddenWhenEmployeeTriesToPromoteDraft() {
        var productId = UUID.randomUUID();
        var draftProduct = new Product(
                productId, "Produit Brouillon", null, "KEV-EMP001", UUID.randomUUID(),
                5000, 0, 0, 0, false, ProductStatus.DRAFT, 0,
                Instant.now(), Instant.now());

        var dto = new UpdateProductUseCase.UpdateProductDto(
                productId, "Produit Brouillon", null, "KEV-EMP001", UUID.randomUUID(),
                5000, null, null, null, UUID.randomUUID(), "EMPLOYEE");

        when(productRepository.findById(productId)).thenReturn(Optional.of(draftProduct));

        var ex = assertThrows(DomainException.class, () -> useCase.execute(dto));
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getDomainCode());
    }

    @Test
    void shouldAcknowledgeDraftNotificationWhenOwnerPromotesDraft() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");

            var productId = UUID.randomUUID();
            var categoryId = UUID.randomUUID();
            var draftProduct = new Product(
                    productId, "Article", null, "KEV-ACK001", categoryId,
                    3000, 0, 0, 0, false, ProductStatus.DRAFT, 0,
                    Instant.now(), Instant.now());

            var actorId = UUID.randomUUID();
            var dto = new UpdateProductUseCase.UpdateProductDto(
                    productId, "Article", null, "KEV-ACK001", categoryId,
                    3000, null, null, null, actorId, "OWNER");

            when(productRepository.findById(productId)).thenReturn(Optional.of(draftProduct));
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            useCase.execute(dto);

            // 12.6a: messaging acknowledges the pending record on this event (DraftValidationTrackingListenerTest)
            verify(eventPublisher).publishEvent((Object) argThat(e -> e instanceof DraftProductValidatedEvent v
                    && v.productId().equals(productId) && v.actorId().equals(actorId) && v.tenantId().equals("kv_test")));
        }
    }

    // ── Story 17.7: catalogue price change event ──────────────────────────────

    private Product active(UUID id, int price) {
        return new Product(id, "Riz", null, "KEV-RIZ001", null,
                price, 0, 0, 0, false, ProductStatus.ACTIVE, 0, Instant.now(), Instant.now());
    }

    @Test
    void priceChanged_publishesPriceChangedEvent_withRoleAndBothPrices() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            var productId = UUID.randomUUID();
            var actorId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(active(productId, 1500)));
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                    1200, null, null, null, actorId, "EMPLOYEE"));

            verify(eventPublisher).publishEvent((Object) argThat(e -> e instanceof ProductPriceChangedEvent v
                    && v.productId().equals(productId) && v.productName().equals("Riz")
                    && v.oldPrice() == 1500 && v.newPrice() == 1200 && v.actorId().equals(actorId)
                    && "EMPLOYEE".equals(v.actorRole()) && "kv_test".equals(v.tenantId())));
        }
    }

    @Test
    void ownerPriceChange_alsoPublished_listenerFiltersTheRole() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            var productId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(active(productId, 1500)));
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                    1000, null, null, null, UUID.randomUUID(), "OWNER"));

            verify(eventPublisher).publishEvent((Object) argThat(e -> e instanceof ProductPriceChangedEvent v
                    && "OWNER".equals(v.actorRole()) && "kv_test".equals(v.tenantId())));
        }
    }

    @Test
    void samePriceOrNoPrice_noPriceChangedEvent() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            var productId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(active(productId, 1500)));
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                    1500, null, null, null, UUID.randomUUID(), "EMPLOYEE"));
            useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, "Riz parfumé", null, null, null,
                    null, 900, null, null, UUID.randomUUID(), "EMPLOYEE"));

            verify(eventPublisher, never()).publishEvent(isA(ProductPriceChangedEvent.class));
            verify(eventPublisher, times(2)).publishEvent((Object) argThat(e ->
                    e instanceof com.keevo.shared.event.catalog.product.ProductUpdatedEvent u
                            && "kv_test".equals(u.tenantId())));
        }
    }

    // ── Story 16.13 (reprise, D2): an actor without PURCHASE_PRICE_VIEW never changes purchase prices ─────────

    @Test
    void employee_update_keeps_the_stored_purchase_price_but_applies_the_rest() {
        var productId = UUID.randomUUID();
        var existing = new Product(productId, "Riz 5kg", null, "KEV-RIZ5KG", null,
                3500, 2800, 150, null, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TenantContext.setCurrentTenant("kv_test01");
        try {
            var result = useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                    3700, 0, 0, null, UUID.randomUUID(), "EMPLOYEE"));
            assertEquals(2800, result.getBuyPriceValue());
            assertEquals(150, result.getTransportCostValue());
            assertEquals("3700 XAF", String.valueOf(result.getPrice()));

            var owner = useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                    null, 3000, 100, null, UUID.randomUUID(), "OWNER"));
            assertEquals(3000, owner.getBuyPriceValue());
            assertEquals(100, owner.getTransportCostValue());

            var unknownRole = useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null,
                    null, null, 1, 1, null, UUID.randomUUID(), null));
            assertEquals(2800, unknownRole.getBuyPriceValue()); // fail closed (stored = existing mock row)

            // Story 19.14 — the authenticated actor decides: a MANAGER-based custom role without « Voir les prix
            // d'achat » never writes them, even though its role name would
            var mapped = new UpdateProductUseCase(productRepository, eventPublisher, objectMapper, productCountPort,
                    tenantPlanQuery, planLimit, transactionLock, stockTracking,
                    new com.keevo.shared.infrastructure.security.PurchasePriceVisibility(
                            new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of(
                                    "m", com.keevo.shared.infrastructure.security.ShippedActorPermissions
                                            .modulePermissions())).getBeanProvider(
                                    com.keevo.shared.module.application.ModulePermissions.class)));
            com.keevo.shared.infrastructure.security.ShippedActorPermissions.authenticateWithCustomRole(
                    UUID.randomUUID(), "EMPLOYEE", UUID.randomUUID(), java.util.Set.of("PURCHASE_PRICE_VIEW"));
            var customEmployee = mapped.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null,
                    null, null, 2900, 120, null, UUID.randomUUID(), "EMPLOYEE"));
            assertEquals(2900, customEmployee.getBuyPriceValue()); // the ticked checkbox lets him write it

            com.keevo.shared.infrastructure.security.ShippedActorPermissions.authenticateWithCustomRole(
                    UUID.randomUUID(), "MANAGER", UUID.randomUUID(), java.util.Set.of("SALE_CREATE"));
            var customManager = mapped.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null,
                    null, null, 1, 1, null, UUID.randomUUID(), "MANAGER"));
            assertEquals(2800, customManager.getBuyPriceValue());
        } finally {
            TenantContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void employee_update_with_only_a_purchase_price_writes_nothing() {
        var productId = UUID.randomUUID();
        var existing = new Product(productId, "Riz 5kg", null, "KEV-RIZ5KG", null,
                3500, 2800, 150, null, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));

        var result = useCase.execute(new UpdateProductUseCase.UpdateProductDto(productId, null, null, null, null,
                null, 0, 0, null, UUID.randomUUID(), "VIEWER"));

        assertEquals(2800, result.getBuyPriceValue());
        verify(productRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
