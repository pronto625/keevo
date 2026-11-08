package com.keevo.invoicing.invoice.domain.port.out;

/**
 * Story 20.7 — the signature size limits, single source {@code keevo.invoice-signature.*}: the raw upload accepted
 * before re-encoding (a phone photo or pad export), and the stored image after re-encoding / downscaling.
 */
public interface InvoiceSignatureLimits {

    /** Largest raw upload accepted (before re-encoding), in bytes. */
    int maxUploadBytes();

    /** Largest stored image (after re-encoding), in bytes. */
    int maxStoredBytes();
}
