package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Test-only access to the package-private catalog product API adapters (Story 12.2b), for tests
 * of other packages that need a real {@link ProductView} or the real adapters over a mock.
 */
public final class CatalogProductApiTestSupport {

    private CatalogProductApiTestSupport() {}

    /** The published view of an internal product, mapped exactly as the catalog adapters do. */
    public static ProductView view(Product product) {
        return ProductViews.of(product);
    }

    /** The real query adapter over the given repository. */
    public static ProductQueryPort queryPort(ProductRepository productRepository) {
        return new ProductQueryAdapter(productRepository);
    }

    /** The real command adapter, wired for {@code register}/{@code changeMinimumThreshold} only. */
    public static ProductCommandPort repositoryCommandPort(ProductRepository productRepository,
                                                           ApplicationEventPublisher eventPublisher) {
        return new ProductCommandAdapter(null, null, null, null, null, productRepository, eventPublisher);
    }
}
