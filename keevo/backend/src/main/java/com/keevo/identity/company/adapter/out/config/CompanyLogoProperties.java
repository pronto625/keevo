package com.keevo.identity.company.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Company logo settings ({@code keevo.company-logo.*}, Story 14.2). The storage directory must be persistent: it
 * holds the only copy of the logo.
 */
@ConfigurationProperties("keevo.company-logo")
public class CompanyLogoProperties {

    /** Root directory: {@code <dir>/<tenant schema>/logo.png|jpg}. */
    private String storageDir = Path.of(System.getProperty("user.home"), "keevo-data", "company-logos").toString();
    /** Largest accepted upload, in bytes (the app compresses the logo before sending it). */
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
