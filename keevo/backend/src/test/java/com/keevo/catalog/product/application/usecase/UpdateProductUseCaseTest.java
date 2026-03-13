package com.keevo.catalog.product.application.usecase;

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
import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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
    private DraftNotificationRepository draftNotificationRepository;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;
    
    @Mock
    private ObjectMapper objectMapper;

    private UpdateProductUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateProductUseCase(productRepository, draftNotificationRepository, eventPublisher, objectMapper);
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
            UUID.randomUUID() // actorId
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
            UUID.randomUUID() // actorId
        );

        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            useCase.execute(dto);
        });
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
            when(draftNotificationRepository.findByProductId(productId)).thenReturn(Optional.empty());

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

            var pending = DraftPendingValidation.create(productId, "Article", actorId,
                    UUID.nameUUIDFromBytes("kv_test".getBytes()));

            when(productRepository.findById(productId)).thenReturn(Optional.of(draftProduct));
            when(productRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(draftNotificationRepository.findByProductId(productId)).thenReturn(Optional.of(pending));

            useCase.execute(dto);

            verify(draftNotificationRepository).saveUpdated(argThat(DraftPendingValidation::isAcknowledged));
        }
    }
}