package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.adapter.out.config.ProductPhotoProperties;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.shared.event.catalog.product.ProductPhotoUpdatedEvent;
import com.keevo.catalog.product.domain.port.out.ProductPhotoNormalizer;
import com.keevo.catalog.product.domain.port.out.ProductPhotoNormalizer.NormalizedPhoto;
import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage;
import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage.StoredPhoto;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Product photo thumbnails, stored server side so every device of the tenant sees them
 * (FR21, epic 2 / epic 4). The app compresses the photo (≈ 400 px, JPEG ≈ 70 %, < 40 KB) before
 * sending it; the server re-checks size and format, re-encodes it (metadata stripped), stores it
 * through {@link ProductPhotoStorage} and sets {@code products.photo_url} to a versioned,
 * authenticated URL — the sync pull then delivers it to the other devices.
 *
 * <p>Rights follow the product edit rules ({@link UpdateProductUseCase}): OWNER and EMPLOYEE,
 * except that only the OWNER may touch a DRAFT product (Story 2.4). Changing the photo never
 * promotes a draft.
 */
@Service
public class ProductPhotoUseCase {

    /** Public path of a product photo; {@code ?v=} changes with every new photo (cache busting). */
    public static String photoUrl(UUID productId, Instant version) {
        return "/api/v1/products/" + productId + "/photo?v=" + version.toEpochMilli();
    }

    private final ProductRepository productRepository;
    private final ProductPhotoStorage storage;
    private final ProductPhotoNormalizer normalizer;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxBytes;
    private final Clock clock;

    @Autowired
    public ProductPhotoUseCase(ProductRepository productRepository, ProductPhotoStorage storage,
                               ProductPhotoNormalizer normalizer, ApplicationEventPublisher eventPublisher,
                               ProductPhotoProperties properties) {
        this(productRepository, storage, normalizer, eventPublisher, properties.getMaxBytes(), Clock.systemUTC());
    }

    public ProductPhotoUseCase(ProductRepository productRepository, ProductPhotoStorage storage,
                               ProductPhotoNormalizer normalizer, ApplicationEventPublisher eventPublisher,
                               int maxBytes, Clock clock) {
        this.productRepository = productRepository;
        this.storage = storage;
        this.normalizer = normalizer;
        this.eventPublisher = eventPublisher;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    /** Who acts: the role drives the draft rule, the name goes to the audit entry. */
    public record Actor(UUID id, String role, String name) {}

    /**
     * Store a new photo for the product.
     *
     * @return the new photo URL (also written to {@code products.photo_url})
     */
    @Transactional
    public String upload(String tenantSchema, UUID productId, byte[] upload, Actor actor) {
        String schema = TenantSchema.validate(tenantSchema);
        Product product = editableProduct(productId, actor);
        if (upload == null || upload.length == 0) {
            throw new DomainException(ErrorCode.PRODUCT_PHOTO_INVALID, "Empty product photo");
        }
        if (upload.length > maxBytes) {
            throw new DomainException(ErrorCode.PRODUCT_PHOTO_TOO_LARGE,
                    "Product photo of " + upload.length + " bytes over " + maxBytes);
        }
        NormalizedPhoto photo = normalizer.normalize(upload);
        boolean hadPhoto = productRepository.findPhotoUrl(productId).isPresent();
        Instant now = clock.instant();
        try {
            storage.store(schema, productId, photo.content(), photo.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Product photo could not be stored", e);
        }
        String url = photoUrl(productId, now);
        productRepository.updatePhotoUrl(productId, url, now);
        eventPublisher.publishEvent(new ProductPhotoUpdatedEvent(productId, product.getName(),
                hadPhoto, true, schema, actor.id(), actor.name(), now));
        return url;
    }

    /** Remove the product photo (« Supprimer la photo »); a product without photo is a no-op. */
    @Transactional
    public void delete(String tenantSchema, UUID productId, Actor actor) {
        String schema = TenantSchema.validate(tenantSchema);
        Product product = editableProduct(productId, actor);
        boolean hadPhoto = productRepository.findPhotoUrl(productId).isPresent();
        if (!hadPhoto) {
            storage.delete(schema, productId); // leftover file, if any
            return;
        }
        Instant now = clock.instant();
        productRepository.updatePhotoUrl(productId, null, now);
        storage.delete(schema, productId);
        eventPublisher.publishEvent(new ProductPhotoUpdatedEvent(productId, product.getName(),
                true, false, schema, actor.id(), actor.name(), now));
    }

    /** The stored photo of a product of the caller's tenant. */
    @Transactional(readOnly = true)
    public StoredPhoto read(String tenantSchema, UUID productId) {
        String schema = TenantSchema.validate(tenantSchema);
        if (productRepository.findById(productId).isEmpty()) {
            throw new DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId);
        }
        try {
            return storage.read(schema, productId)
                    .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_PHOTO_NOT_FOUND,
                            "No photo for product " + productId));
        } catch (IOException e) {
            throw new UncheckedIOException("Product photo could not be read", e);
        }
    }

    private Product editableProduct(UUID productId, Actor actor) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));
        if (product.getStatus() == ProductStatus.DRAFT && !"OWNER".equals(actor.role())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut modifier un produit en brouillon");
        }
        return product;
    }
}
