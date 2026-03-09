package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.service.MarginCalculation;
import com.keevo.catalog.product.domain.service.MarginThreshold;
import com.keevo.catalog.product.domain.service.PricingCalculator;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * GetProductPricingUseCase — Computes real-time margin information for a product.
 *
 * <p>This use case does NOT modify the product. It fetches the product, runs
 * {@link PricingCalculator#calculateMargin} on its current prices, and returns
 * a {@link ProductPricingDto} for frontend display.
 *
 * <p>GoF Pattern: Facade — hides the PricingCalculator and ProductRepository behind
 * a single, simple execute() method.
 */
@Service
public class GetProductPricingUseCase {

    private final ProductRepository productRepository;
    private final PricingCalculator pricingCalculator;

    public GetProductPricingUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
        this.pricingCalculator = new PricingCalculator();
    }

    /**
     * Pricing result DTO for the REST endpoint and frontend.
     */
    public record ProductPricingDto(
            UUID productId,
            int sellingPrice,
            int buyPrice,
            int transportCostValue,
            int totalCost,
            int grossMarginXaf,
            double marginPercentage,
            boolean isLoss,
            MarginThreshold marginThreshold
    ) {}

    /**
     * Execute the pricing calculation.
     *
     * @param productId the product to price
     * @return {@link ProductPricingDto} with all margin fields
     * @throws DomainException {@link ErrorCode#PRODUCT_NOT_FOUND} if the product is not found (→ 404)
     */
    public ProductPricingDto execute(UUID productId) {
        var product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + productId));

        MarginCalculation calc = pricingCalculator.calculateMargin(
                product.getBuyPrice(),
                product.getTransportCost(),
                product.getPrice()
        );

        MarginThreshold threshold = MarginThreshold.categorizeMargin(calc.marginPercentage());

        return new ProductPricingDto(
                product.getId(),
                product.getPriceValue(),
                product.getBuyPriceValue(),
                product.getTransportCostValue(),
                calc.totalCost().value(),
                calc.grossMarginXaf(),
                calc.marginPercentage(),
                calc.isLoss(),
                threshold
        );
    }
}
