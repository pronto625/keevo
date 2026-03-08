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
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Basic TDD Test for UpdateProductUseCase
 */
@ExtendWith(MockitoExtension.class)
class UpdateProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;
    
    @Mock
    private ObjectMapper objectMapper;

    private UpdateProductUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateProductUseCase(productRepository, eventPublisher, objectMapper);
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
            UUID.randomUUID() // actorId
        );

        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            useCase.execute(dto);
        });
    }
}