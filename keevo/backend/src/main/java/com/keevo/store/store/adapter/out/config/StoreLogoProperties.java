package com.keevo.store.store.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Store logo settings ({@code keevo.store-logo.*}, Story 19.12). The storage directory must be persistent: it holds
 * the only copy of the logos.
 */
@ConfigurationProperties("keevo.store-logo")
public class StoreLogoProperties {

    /** Root directory: {@code <dir>/<tenant schema>/<store id>.png|jpg}. */
    private String storageDir = Path.of(System.getProperty("user.home"), "keevo-data", "store-logos").toString();
    /** Largest accepted upload (and stored logo), in bytes. */
    private int maxBytes = 200 * 1024;
    /** Longest side of the stored logo, in pixels (larger images are downscaled). */
    private int maxDimension = 600;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getMaxBytes() { return maxBytes; }
    public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
    public int getMaxDimension() { return maxDimension; }
    public void setMaxDimension(int maxDimension) { this.maxDimension = maxDimension; }
}
