package com.keevo.store.store.adapter.out.image;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.image.ImageReencoder;
import com.keevo.store.store.adapter.out.config.StoreLogoProperties;
import com.keevo.store.store.domain.port.out.StoreLogoNormalizer;
import com.keevo.store.store.domain.port.out.StoreLogoStorage.StoredLogo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Store logo normalizer (Story 19.12) over the shared {@link ImageReencoder} (the company logo's core): PNG / JPEG
 * recognised by its bytes, re-encoded without metadata, longest side capped at {@code keevo.store-logo.max-dimension}.
 * An opaque PNG heavier than {@code keevo.store-logo.max-bytes} becomes a JPEG; a result still heavier →
 * {@code STORE_LOGO_TOO_HEAVY}; anything else → {@code STORE_LOGO_INVALID}.
 */
@Component
public class ImageIoStoreLogoNormalizer implements StoreLogoNormalizer {

    private final ImageReencoder reencoder;
    private final int maxBytes;

    @Autowired
    public ImageIoStoreLogoNormalizer(StoreLogoProperties properties) {
        this(properties.getMaxDimension(), properties.getMaxBytes());
    }

    public ImageIoStoreLogoNormalizer(int maxDimension, int maxBytes) {
        this.reencoder = new ImageReencoder(maxDimension);
        this.maxBytes = maxBytes;
    }

    @Override
    public StoredLogo normalize(byte[] upload) {
        ImageReencoder.Image image;
        try {
            image = reencoder.reencode(upload, maxBytes);
        } catch (ImageReencoder.InvalidImageException e) {
            throw new DomainException(ErrorCode.STORE_LOGO_INVALID, "Unsupported or corrupt store logo");
        }
        if (image.content().length > maxBytes) {
            throw new DomainException(ErrorCode.STORE_LOGO_TOO_HEAVY, "Normalized store logo too heavy");
        }
        return new StoredLogo(image.content(), image.contentType());
    }
}
