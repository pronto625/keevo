package com.keevo.catalog.product.domain.port.out;

/**
 * Checks an uploaded product photo is a real JPEG / PNG / WebP image and turns it into the
 * stored thumbnail (metadata stripped, longest side capped).
 */
public interface ProductPhotoNormalizer {

    /**
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_PHOTO_INVALID when the
     *         bytes are not a supported, decodable image
     */
    NormalizedPhoto normalize(byte[] upload);

    record NormalizedPhoto(byte[] content, String contentType) {}
}
