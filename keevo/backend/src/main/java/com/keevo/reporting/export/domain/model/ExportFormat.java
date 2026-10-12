package com.keevo.reporting.export.domain.model;

/** Export file formats (Story v1s-18-1). CSV on every plan; XLSX and PDF are Premium. */
public enum ExportFormat {
    CSV,
    XLSX,
    PDF;

    /** Premium-only formats carry their gating feature name ({@code details.feature}). */
    public String premiumFeature() {
        return switch (this) {
            case CSV -> null;
            case XLSX -> "EXPORT_XLSX";
            case PDF -> "EXPORT_PDF";
        };
    }
}
