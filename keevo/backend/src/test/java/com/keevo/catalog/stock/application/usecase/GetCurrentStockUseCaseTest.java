package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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
 * GetCurrentStockUseCaseTest — unit tests for current stock snapshot.
 * Story 2.3.
 */
@ExtendWith(MockitoExtension.class)
class GetCurrentStockUseCaseTest {

    @Mock private ProductRepository productRepository;
    @Mock private StockLevelRepository stockLevelRepository;
    @InjectMocks private GetCurrentStockUseCase useCase;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();

    private Product makeProduct(int minimumThreshold) {
        return new Product(
            PRODUCT_ID, "Test Product", null, "KEV-TST001",
            UUID.randomUUID(), 1000, 500, 0, null,
            false, ProductStatus.ACTIVE, minimumThreshold,
            Instant.now(), Instant.now());
    }

    private StockLevel makeLevel(int quantity) {
        return new StockLevel(UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
                              quantity, Instant.now());
    }

    @Test
    void should_return_snapshot_with_product_and_levels() {
        Product product = makeProduct(5);
        List<StockLevel> levels = List.of(makeLevel(10));

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(stockLevelRepository.findAllByProduct(PRODUCT_ID)).thenReturn(levels);

        GetCurrentStockUseCase.StockSnapshot result = useCase.execute(PRODUCT_ID);

        assertThat(result.product()).isEqualTo(product);
        assertThat(result.stockLevels()).hasSize(1);
        verify(stockLevelRepository).findAllByProduct(PRODUCT_ID);
    }

    @Test
    void should_throw_PRODUCT_NOT_FOUND_when_product_absent() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));

        verifyNoInteractions(stockLevelRepository);
    }

    @Test
    void should_return_empty_levels_when_product_never_stocked() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(makeProduct(0)));
        when(stockLevelRepository.findAllByProduct(PRODUCT_ID)).thenReturn(List.of());

        GetCurrentStockUseCase.StockSnapshot result = useCase.execute(PRODUCT_ID);

        assertThat(result.stockLevels()).isEmpty();
    }

    @Test
    void isLowInAnyStore_should_return_false_when_threshold_is_zero() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(makeProduct(0)));
        when(stockLevelRepository.findAllByProduct(PRODUCT_ID)).thenReturn(List.of(makeLevel(0)));

        GetCurrentStockUseCase.StockSnapshot result = useCase.execute(PRODUCT_ID);

        assertThat(result.isLowInAnyStore()).isFalse();
    }

    @Test
    void isLowInAnyStore_should_return_true_when_quantity_at_threshold() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(makeProduct(5)));
        when(stockLevelRepository.findAllByProduct(PRODUCT_ID)).thenReturn(List.of(makeLevel(5)));

        GetCurrentStockUseCase.StockSnapshot result = useCase.execute(PRODUCT_ID);

        assertThat(result.isLowInAnyStore()).isTrue();
    }

    @Test
    void isLowInAnyStore_should_return_true_when_quantity_below_threshold() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(makeProduct(10)));
        when(stockLevelRepository.findAllByProduct(PRODUCT_ID)).thenReturn(List.of(makeLevel(3)));

        GetCurrentStockUseCase.StockSnapshot result = useCase.execute(PRODUCT_ID);

        assertThat(result.isLowInAnyStore()).isTrue();
    }

    @Test
    void isLowInAnyStore_should_return_false_when_all_quantities_above_threshold() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(makeProduct(5)));
        when(stockLevelRepository.findAllByProduct(PRODUCT_ID)).thenReturn(
            List.of(makeLevel(6), makeLevel(20)));

        GetCurrentStockUseCase.StockSnapshot result = useCase.execute(PRODUCT_ID);

        assertThat(result.isLowInAnyStore()).isFalse();
    }

    @Test
    void isLowInAnyStore_should_return_true_when_at_least_one_store_is_low() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(makeProduct(5)));
        when(stockLevelRepository.findAllByProduct(PRODUCT_ID)).thenReturn(
            List.of(makeLevel(20), makeLevel(2))); // one above, one below

        GetCurrentStockUseCase.StockSnapshot result = useCase.execute(PRODUCT_ID);

        assertThat(result.isLowInAnyStore()).isTrue();
    }
}
