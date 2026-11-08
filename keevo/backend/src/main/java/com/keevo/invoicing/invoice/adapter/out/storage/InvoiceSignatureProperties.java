package com.keevo.invoicing.invoice.adapter.out.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Signature image settings ({@code keevo.invoice-signature.*}, Story 20.7) — same shape as the store logo settings.
 * The storage directory must be persistent: it holds the only copy of the signatures.
 */
@ConfigurationProperties("keevo.invoice-signature")
public class InvoiceSignatureProperties {

    /** Root directory: {@code <dir>/<tenant schema>/<invoice id>-<role>-<hex>.png|jpg}. */
    private String storageDir = Path.of(System.getProperty("user.home"), "keevo-data", "invoice-signatures").toString();
    /** Largest accepted upload (and stored image), in bytes. */
    private int maxBytes = 200 * 1024;
    /** Longest side of the stored image, in pixels (larger images are downscaled). */
    private int maxDimension = 800;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getMaxBytes() { return maxBytes; }
    public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
    public int getMaxDimension() { return maxDimension; }
    public void setMaxDimension(int maxDimension) { this.maxDimension = maxDimension; }
}
