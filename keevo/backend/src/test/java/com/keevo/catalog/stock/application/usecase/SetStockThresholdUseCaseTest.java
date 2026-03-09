package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SetStockThresholdUseCaseTest — Story 2.3.
 */
@ExtendWith(MockitoExtension.class)
class SetStockThresholdUseCaseTest {

    @Mock private ProductRepository productRepository;
    @InjectMocks private SetStockThresholdUseCase useCase;

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    private Product product(int threshold) {
        return new Product(PRODUCT_ID, "Widget", "d", "KEV-ABCDEF",
            UUID.randomUUID(), 1000, 800, 50, 10,
            false, ProductStatus.ACTIVE, threshold,
            Instant.now(), Instant.now());
    }

    @Test
    void should_update_minimum_threshold() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(0)));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Product result = useCase.execute(PRODUCT_ID, 15);

        assertThat(result.getMinimumThreshold()).isEqualTo(15);
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void should_accept_zero_threshold() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(10)));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Product result = useCase.execute(PRODUCT_ID, 0);
        assertThat(result.getMinimumThreshold()).isEqualTo(0);
    }

    @Test
    void should_throw_for_negative_threshold() {
        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");

        verify(productRepository, never()).save(any());
    }

    @Test
    void should_throw_product_not_found() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(PRODUCT_ID, 5))
            .isInstanceOf(DomainException.class)
            .satisfies(ex ->
                assertThat(((DomainException) ex).getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
