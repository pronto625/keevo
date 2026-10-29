package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.application.usecase.ProductPhotoUseCase.Actor;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.shared.event.catalog.product.ProductPhotoUpdatedEvent;
import com.keevo.catalog.product.domain.port.out.ProductPhotoNormalizer;
import com.keevo.catalog.product.domain.port.out.ProductPhotoNormalizer.NormalizedPhoto;
import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage;
import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage.StoredPhoto;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductPhotoUseCase")
class ProductPhotoUseCaseTest {

    private static final String SCHEMA = "kv_abc123";
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final int MAX = 200 * 1024;

    @Mock ProductRepository productRepository;
    @Mock ProductPhotoStorage storage;
    @Mock ProductPhotoNormalizer normalizer;
    @Mock ApplicationEventPublisher eventPublisher;

    private ProductPhotoUseCase useCase;
    private final UUID productId = UUID.randomUUID();
    private final Actor owner = new Actor(UUID.randomUUID(), "OWNER", "Simon");
    private final Actor employee = new Actor(UUID.randomUUID(), "EMPLOYEE", "Awa");

    @BeforeEach
    void setUp() {
        useCase = new ProductPhotoUseCase(productRepository, storage, normalizer, eventPublisher, MAX,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(normalizer.normalize(any())).thenReturn(new NormalizedPhoto(new byte[]{7, 7}, "image/jpeg"));
        when(productRepository.findPhotoUrl(productId)).thenReturn(Optional.empty());
    }

    private void product(ProductStatus status) {
        when(productRepository.findById(productId)).thenReturn(Optional.of(new Product(productId, "Savon",
                null, "KEV-ABC123", null, 500, 300, 0, 0, false, status, NOW, NOW)));
    }

    @Test
    @DisplayName("upload: normalised bytes stored for the tenant, versioned URL set, audit event published")
    void upload_storesAndSetsUrl() throws Exception {
        product(ProductStatus.ACTIVE);

        String url = useCase.upload(SCHEMA, productId, new byte[]{1, 2, 3}, employee);

        assertThat(url).isEqualTo("/api/v1/products/" + productId + "/photo?v=" + NOW.toEpochMilli());
        verify(storage).store(SCHEMA, productId, new byte[]{7, 7}, "image/jpeg");
        verify(productRepository).updatePhotoUrl(productId, url, NOW);
        ArgumentCaptor<ProductPhotoUpdatedEvent> event = ArgumentCaptor.forClass(ProductPhotoUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().hadPhoto()).isFalse();
        assertThat(event.getValue().hasPhoto()).isTrue();
        assertThat(event.getValue().actorName()).isEqualTo("Awa");
        assertThat(event.getValue().productName()).isEqualTo("Savon");
    }

    @Test
    @DisplayName("upload replacing a photo: event says it had one")
    void upload_replace() {
        product(ProductStatus.ACTIVE);
        when(productRepository.findPhotoUrl(productId)).thenReturn(Optional.of("/api/v1/products/x/photo?v=1"));

        useCase.upload(SCHEMA, productId, new byte[]{1}, owner);

        ArgumentCaptor<ProductPhotoUpdatedEvent> event = ArgumentCaptor.forClass(ProductPhotoUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().hadPhoto()).isTrue();
    }

    @Test
    @DisplayName("upload over 200 KB → PRODUCT_PHOTO_TOO_LARGE, nothing stored")
    void upload_tooLarge() throws Exception {
        product(ProductStatus.ACTIVE);

        assertCode(() -> useCase.upload(SCHEMA, productId, new byte[MAX + 1], owner), "PRODUCT_PHOTO_TOO_LARGE");
        verify(storage, never()).store(anyString(), any(), any(), anyString());
        verify(productRepository, never()).updatePhotoUrl(any(), any(), any());
    }

    @Test
    @DisplayName("upload of exactly 200 KB is accepted")
    void upload_atLimit() {
        product(ProductStatus.ACTIVE);

        assertThat(useCase.upload(SCHEMA, productId, new byte[MAX], owner)).isNotBlank();
    }

    @Test
    @DisplayName("empty upload or invalid image → PRODUCT_PHOTO_INVALID, nothing stored")
    void upload_invalid() throws Exception {
        product(ProductStatus.ACTIVE);
        assertCode(() -> useCase.upload(SCHEMA, productId, new byte[0], owner), "PRODUCT_PHOTO_INVALID");

        when(normalizer.normalize(any())).thenThrow(new DomainException("PRODUCT_PHOTO_INVALID", "bad"));
        assertCode(() -> useCase.upload(SCHEMA, productId, new byte[]{1}, owner), "PRODUCT_PHOTO_INVALID");
        verify(storage, never()).store(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("unknown product (or a product of another tenant) → PRODUCT_NOT_FOUND")
    void upload_unknownProduct() {
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertCode(() -> useCase.upload(SCHEMA, productId, new byte[]{1}, owner), "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("draft rule: an EMPLOYEE cannot change the photo of a DRAFT product, the OWNER can")
    void draftRule() {
        product(ProductStatus.DRAFT);

        assertCode(() -> useCase.upload(SCHEMA, productId, new byte[]{1}, employee), "FORBIDDEN");
        assertCode(() -> useCase.delete(SCHEMA, productId, employee), "FORBIDDEN");
        assertThat(useCase.upload(SCHEMA, productId, new byte[]{1}, owner)).isNotBlank();
    }

    @Test
    @DisplayName("an invalid tenant schema is refused before anything else")
    void invalidSchema() {
        assertThatThrownBy(() -> useCase.upload("public", productId, new byte[]{1}, owner))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("delete: URL cleared (updatedAt bumped), file deleted, audit event")
    void delete_clears() {
        product(ProductStatus.ACTIVE);
        when(productRepository.findPhotoUrl(productId)).thenReturn(Optional.of("/api/v1/products/x/photo?v=1"));

        useCase.delete(SCHEMA, productId, employee);

        verify(productRepository).updatePhotoUrl(productId, null, NOW);
        verify(storage).delete(SCHEMA, productId);
        ArgumentCaptor<ProductPhotoUpdatedEvent> event = ArgumentCaptor.forClass(ProductPhotoUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().hasPhoto()).isFalse();
    }

    @Test
    @DisplayName("delete without photo: no DB write, no audit event")
    void delete_noPhoto() {
        product(ProductStatus.ACTIVE);

        useCase.delete(SCHEMA, productId, owner);

        verify(productRepository, never()).updatePhotoUrl(any(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("read: the tenant's stored photo; none → PRODUCT_PHOTO_NOT_FOUND; unknown product → PRODUCT_NOT_FOUND")
    void read() throws Exception {
        product(ProductStatus.ACTIVE);
        when(storage.read(SCHEMA, productId)).thenReturn(Optional.of(new StoredPhoto(new byte[]{5}, "image/jpeg")));
        assertThat(useCase.read(SCHEMA, productId).content()).containsExactly(5);

        when(storage.read(SCHEMA, productId)).thenReturn(Optional.empty());
        assertCode(() -> useCase.read(SCHEMA, productId), "PRODUCT_PHOTO_NOT_FOUND");

        when(productRepository.findById(productId)).thenReturn(Optional.empty());
        assertCode(() -> useCase.read(SCHEMA, productId), "PRODUCT_NOT_FOUND");
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(code);
    }
}
