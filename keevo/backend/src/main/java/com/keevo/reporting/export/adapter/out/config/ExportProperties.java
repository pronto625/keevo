package com.keevo.reporting.export.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Export module settings ({@code keevo.export.*}), Story v1s-18-1.
 */
@ConfigurationProperties("keevo.export")
public class ExportProperties {

    /** Above this many rows a Premium export runs in the background. */
    private long syncRowThreshold = 5000;
    /** Keyset page size used to read providers. */
    private int pageSize = 1000;
    /** How long a READY background file stays downloadable. */
    private Duration retention = Duration.ofHours(24);
    /** Root directory of background export files. */
    private String storageDir = Path.of(System.getProperty("java.io.tmpdir"), "keevo-exports").toString();
    /** HMAC secret of signed download links; blank = random per boot (links die on restart). */
    private String linkSecret = "";
    /** Accepted export requests per tenant per hour (fixed window). */
    private int maxRequestsPerHour = 30;
    /** Maximum PENDING + RUNNING jobs per tenant. */
    private int maxActiveJobs = 3;
    /** PENDING / RUNNING jobs older than this are failed by the purge (interrupted). */
    private Duration staleJobAfter = Duration.ofHours(2);
    /** Data rows one PDF may hold (Story v1s-18-3); a larger request is refused (422). */
    private int pdfMaxRows = 2000;
    /** Closures one « Fin de journée » PDF may hold (Story v1s-18-3). */
    private int pdfMaxClosures = 31;

    public long getSyncRowThreshold() { return syncRowThreshold; }
    public void setSyncRowThreshold(long syncRowThreshold) { this.syncRowThreshold = syncRowThreshold; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public String getLinkSecret() { return linkSecret; }
    public void setLinkSecret(String linkSecret) { this.linkSecret = linkSecret; }
    public int getMaxRequestsPerHour() { return maxRequestsPerHour; }
    public void setMaxRequestsPerHour(int maxRequestsPerHour) { this.maxRequestsPerHour = maxRequestsPerHour; }
    public int getMaxActiveJobs() { return maxActiveJobs; }
    public void setMaxActiveJobs(int maxActiveJobs) { this.maxActiveJobs = maxActiveJobs; }
    public Duration getStaleJobAfter() { return staleJobAfter; }
    public void setStaleJobAfter(Duration staleJobAfter) { this.staleJobAfter = staleJobAfter; }
    public int getPdfMaxRows() { return pdfMaxRows; }
    public void setPdfMaxRows(int pdfMaxRows) { this.pdfMaxRows = pdfMaxRows; }
    public int getPdfMaxClosures() { return pdfMaxClosures; }
    public void setPdfMaxClosures(int pdfMaxClosures) { this.pdfMaxClosures = pdfMaxClosures; }
}
