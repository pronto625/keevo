package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.service.MarginThreshold;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * GetProductPricingUseCaseTest — TDD tests written BEFORE implementation (RED phase).
 *
 * <p>Tests the pricing calculation use case that provides real-time margin data
 * for a product without modifying the product.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetProductPricingUseCase")
class GetProductPricingUseCaseTest {

    @Mock
    private ProductRepository productRepository;

    private GetProductPricingUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetProductPricingUseCase(productRepository);
    }

    private Product buildProduct(UUID id, int price, int buyPrice, int transportCost) {
        return new Product(
                id,
                "Test Product",
                "Description",
                "KEV-ABC123",
                UUID.randomUUID(),
                price,
                buyPrice,
                transportCost,
                10,
                false,
                ProductStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("should return pricing DTO with margin for profitable product")
    void should_return_pricing_dto_for_profitable_product() {
        // buyPrice=3000, transportCost=500, price=5000
        // totalCost=3500, grossMargin=1500, marginPct=1500/3500*100=42.86% (AC2: base=totalCost)
        var productId = UUID.randomUUID();
        var product = buildProduct(productId, 5000, 3000, 500);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        var result = useCase.execute(productId);

        assertThat(result.productId()).isEqualTo(productId);
        assertThat(result.sellingPrice()).isEqualTo(5000);
        assertThat(result.totalCost()).isEqualTo(3500);
        assertThat(result.grossMarginXaf()).isEqualTo(1500);
        assertThat(result.marginPercentage()).isCloseTo(42.86, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.isLoss()).isFalse();
        assertThat(result.marginThreshold()).isEqualTo(MarginThreshold.PROFITABLE);
    }

    @Test
    @DisplayName("should return LOSS threshold when selling below cost")
    void should_return_loss_threshold_when_selling_below_cost() {
        // buyPrice=4000, transportCost=500, price=3000 → loss
        var productId = UUID.randomUUID();
        var product = buildProduct(productId, 3000, 4000, 500);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        var result = useCase.execute(productId);

        assertThat(result.isLoss()).isTrue();
        assertThat(result.marginThreshold()).isEqualTo(MarginThreshold.LOSS);
    }

    @Test
    @DisplayName("should return MODERATE threshold for 10-19% margin")
    void should_return_moderate_threshold() {
        // buyPrice=4000, transportCost=0, price=4500 → 11.1% margin → MODERATE
        var productId = UUID.randomUUID();
        var product = buildProduct(productId, 4500, 4000, 0);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        var result = useCase.execute(productId);

        assertThat(result.marginThreshold()).isEqualTo(MarginThreshold.MODERATE);
    }

    @Test
    @DisplayName("should throw when product not found")
    void should_throw_when_product_not_found() {
        var productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(productId))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Product not found");
    }
}
