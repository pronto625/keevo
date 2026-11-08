package com.keevo.invoicing.invoice.domain.port.out;

/**
 * Story 20.7 — turns an uploaded signature into the stored image: PNG or JPEG recognised by its bytes (never the
 * declared type), re-encoded without metadata, size capped. Refused → {@code INVOICE_SIGNATURE_INVALID} /
 * {@code INVOICE_SIGNATURE_TOO_LARGE}.
 */
public interface InvoiceSignatureNormalizer {

    InvoiceSignatureStorage.StoredImage normalize(byte[] upload);
}
