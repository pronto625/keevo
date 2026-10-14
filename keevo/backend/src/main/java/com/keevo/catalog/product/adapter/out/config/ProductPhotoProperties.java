package com.keevo.catalog.product.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Product photo settings ({@code keevo.product-photo.*}). The storage directory must be
 * persistent (not a temp dir): photos are the only copy of what the other devices display.
 */
@ConfigurationProperties("keevo.product-photo")
public class ProductPhotoProperties {

    /** Root directory of product photos: {@code <dir>/<tenant schema>/<product id>.jpg|webp}. */
    private String storageDir = Path.of(System.getProperty("user.home"), "keevo-data", "product-photos").toString();
    /** Largest accepted upload, in bytes (the app sends ~40 KB thumbnails). */
    private int maxBytes = 200 * 1024;
    /** Longest side of the stored thumbnail, in pixels (larger images are downscaled). */
    private int maxDimension = 400;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getMaxBytes() { return maxBytes; }
    public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
    public int getMaxDimension() { return maxDimension; }
    public void setMaxDimension(int maxDimension) { this.maxDimension = maxDimension; }
}
