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

/**
 * Basic TDD Test for ArchiveProductUseCase
 */
@ExtendWith(MockitoExtension.class)
class ArchiveProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ArchiveProductUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ArchiveProductUseCase(productRepository, eventPublisher);
    }

    @Test
    void should_archive_existing_product() {
        // Given
        var productId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var product = new Product(
            productId,
            "Test Product",
            "Description",
            "KEV-TST123",
            UUID.randomUUID(),
            null, // price
            null, // buyPrice
            null, // stockQuantity
            false, // not archived
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        var dto = new ArchiveProductUseCase.ArchiveProductDto(productId, actorId);

        // When
        useCase.execute(dto);

        // Then
        verify(productRepository).archive(productId);
    }

    @Test
    void should_throw_exception_when_product_not_found() {
        // Given
        var productId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        var dto = new ArchiveProductUseCase.ArchiveProductDto(productId, actorId);

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            useCase.execute(dto);
        });

        verify(productRepository, never()).archive(any());
    }
}