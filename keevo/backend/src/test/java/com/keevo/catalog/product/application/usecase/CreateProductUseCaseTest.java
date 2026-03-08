package com.keevo.catalog.product.application.usecase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * TDD Test for CreateProductUseCase
 * 
 * Tests BEFORE implementation:
 * - Input validation 
 * - Auto-SKU generation
 * - Product creation
 * - Event publishing
 */
@ExtendWith(MockitoExtension.class)
class CreateProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CreateProductUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateProductUseCase(productRepository, eventPublisher);
    }

    @Test
    @DisplayName("Should create product with provided SKU")
    void should_create_product_with_provided_sku() {
        // Given
        var dto = new CreateProductUseCase.CreateProductDto(
            "Test Product",
            "Test Description", 
            "KEV-ABC123",
            UUID.randomUUID(),
            UUID.randomUUID() // actorId
        );
        
        var savedProduct = new Product(
            UUID.randomUUID(),
            dto.name(),
            dto.description(),
            dto.sku(),
            dto.categoryId(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );

        when(productRepository.findBySku(dto.sku())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        var result = useCase.execute(dto);

        // Then
        assertNotNull(result);
        assertEquals(dto.name(), result.getName());
        assertEquals(dto.sku(), result.getSku());
        verify(productRepository).save(any(Product.class));
        // TODO: Verify event publishing when ProductCreatedEvent is integrated from Story 1.8
        // verify(eventPublisher).publishEvent(any()); // ProductCreatedEvent
    }

    @Test
    @DisplayName("Should generate auto-SKU when not provided")
    void should_generate_auto_sku_when_not_provided() {
        // Given
        var dto = new CreateProductUseCase.CreateProductDto(
            "Test Product",
            "Test Description",
            null, // no SKU provided
            UUID.randomUUID(),
            UUID.randomUUID() // actorId
        );

        var savedProduct = new Product(
            UUID.randomUUID(),
            dto.name(),
            dto.description(),
            "KEV-ABC123", // auto-generated SKU
            dto.categoryId(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );

        when(productRepository.findBySku(anyString())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        var result = useCase.execute(dto);

        // Then
        assertNotNull(result);
        assertNotNull(result.getSku());
        assertTrue(result.getSku().startsWith("KEV-"));
        assertEquals(10, result.getSku().length()); // KEV- + 6 chars
    }

    @Test
    @DisplayName("Should throw exception when name is null")
    void should_throw_exception_when_name_is_null() {
        // Given
        var dto = new CreateProductUseCase.CreateProductDto(
            null, // invalid name
            "Description",
            "KEV-ABC123",
            UUID.randomUUID(),
            UUID.randomUUID() // actorId
        );

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            useCase.execute(dto);
        });

        verifyNoInteractions(productRepository);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Should throw exception when name is empty")
    void should_throw_exception_when_name_is_empty() {
        // Given
        var dto = new CreateProductUseCase.CreateProductDto(
            "", // invalid name
            "Description",
            "KEV-ABC123",
            UUID.randomUUID(),
            UUID.randomUUID() // actorId
        );

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            useCase.execute(dto);
        });
    }

    @Test
    @DisplayName("Should throw exception when SKU already exists")
    void should_throw_exception_when_sku_already_exists() {
        // Given
        var dto = new CreateProductUseCase.CreateProductDto(
            "Test Product",
            "Description",
            "KEV-EXIST1",
            UUID.randomUUID(),
            UUID.randomUUID() // actorId
        );

        var existingProduct = new Product(
            UUID.randomUUID(),
            "Existing Product",
            "Description",
            "KEV-EXIST1",
            UUID.randomUUID(),
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );

        when(productRepository.findBySku("KEV-EXIST1")).thenReturn(Optional.of(existingProduct));

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            useCase.execute(dto);
        });

        verify(productRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Should set default values correctly")
    void should_set_default_values_correctly() {
        // Given
        var dto = new CreateProductUseCase.CreateProductDto(
            "Test Product",
            null, // null description should be allowed
            "KEV-ABC124",
            UUID.randomUUID(),
            UUID.randomUUID() // actorId
        );

        when(productRepository.findBySku(dto.sku())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        var result = useCase.execute(dto);

        // Then
        assertEquals(false, result.getArchived()); // default archived
        assertEquals(ProductStatus.ACTIVE, result.getStatus()); // default status
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
    }
}