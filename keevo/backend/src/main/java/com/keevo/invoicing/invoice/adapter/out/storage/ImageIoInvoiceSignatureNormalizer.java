package com.keevo.invoicing.invoice.adapter.out.storage;

import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureNormalizer;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureStorage.StoredImage;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.image.ImageReencoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Signature normalizer (Story 20.7) over the shared {@link ImageReencoder} (the logos' core): PNG / JPEG recognised by
 * its bytes, re-encoded without metadata (a PNG keeps its transparency — a signature pad draws on a transparent
 * background), longest side capped at {@code keevo.invoice-signature.max-dimension}. Result over
 * {@code keevo.invoice-signature.max-bytes} → {@code INVOICE_SIGNATURE_TOO_LARGE}; anything else →
 * {@code INVOICE_SIGNATURE_INVALID}.
 */
@Component
public class ImageIoInvoiceSignatureNormalizer implements InvoiceSignatureNormalizer {

    private final ImageReencoder reencoder;
    private final int maxBytes;

    @Autowired
    public ImageIoInvoiceSignatureNormalizer(InvoiceSignatureProperties properties) {
        this(properties.getMaxDimension(), properties.getMaxBytes());
    }

    public ImageIoInvoiceSignatureNormalizer(int maxDimension, int maxBytes) {
        this.reencoder = new ImageReencoder(maxDimension);
        this.maxBytes = maxBytes;
    }

    @Override
    public StoredImage normalize(byte[] upload) {
        ImageReencoder.Image image;
        try {
            image = reencoder.reencode(upload, maxBytes);
        } catch (ImageReencoder.InvalidImageException e) {
            throw new DomainException(ErrorCode.INVOICE_SIGNATURE_INVALID, "Unsupported or corrupt signature image");
        }
        if (image.content().length > maxBytes) {
            throw new DomainException(ErrorCode.INVOICE_SIGNATURE_TOO_LARGE, "Normalized signature too heavy");
        }
        return new StoredImage(image.content(), image.contentType());
    }
}
