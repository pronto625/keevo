package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;
import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityQuery;
import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityUseCase;
import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.StoreType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * GetCrossStoreAvailabilityServiceTest — TDD RED phase tests for Story 3.4.
 * Tests cross-store product availability query.
 */
@ExtendWith(MockitoExtension.class)
class GetCrossStoreAvailabilityServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private MultiStoreStockRepository multiStoreStockRepository;

    @InjectMocks private GetCrossStoreAvailabilityService service;

    private final UUID productId = UUID.randomUUID();
    private final UUID storeId1  = UUID.randomUUID();
    private final UUID storeId2  = UUID.randomUUID();

    private Product sampleProduct() {
        return new Product(productId, "Produit Test", null, "KEV-ABCDEF",
                null, 1000, 500, 0, 0, false, null, 0, Instant.now(), Instant.now());
    }

    private CrossStoreAvailabilityEntry sampleEntry(UUID storeId, int qty) {
        return new CrossStoreAvailabilityEntry(storeId, "Boutique A", StoreType.STORE,
                qty, 2, qty > 0 && qty <= 2, Instant.now());
    }

    @Test
    void execute_shouldReturnAvailabilityEntries_whenProductExists() {
        var product = sampleProduct();
        var entries = List.of(sampleEntry(storeId1, 10), sampleEntry(storeId2, 0));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(multiStoreStockRepository.getProductAvailability(productId)).thenReturn(entries);

        GetCrossStoreAvailabilityUseCase.Result result =
                service.execute(new GetCrossStoreAvailabilityQuery(productId));

        assertThat(result.product()).isEqualTo(product);
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).quantity()).isEqualTo(10);
    }

    @Test
    void execute_shouldThrowProductNotFound_whenProductIdUnknown() {
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(new GetCrossStoreAvailabilityQuery(productId)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        verifyNoInteractions(multiStoreStockRepository);
    }

    @Test
    void execute_shouldReturnEmptyList_whenNoStockLevelsExist() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(sampleProduct()));
        when(multiStoreStockRepository.getProductAvailability(productId)).thenReturn(List.of());

        GetCrossStoreAvailabilityUseCase.Result result =
                service.execute(new GetCrossStoreAvailabilityQuery(productId));

        assertThat(result.entries()).isEmpty();
    }

    @Test
    void execute_shouldDelegateToRepository_withCorrectProductId() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(sampleProduct()));
        when(multiStoreStockRepository.getProductAvailability(productId)).thenReturn(List.of());

        service.execute(new GetCrossStoreAvailabilityQuery(productId));

        verify(productRepository).findById(productId);
        verify(multiStoreStockRepository).getProductAvailability(productId);
    }
}
