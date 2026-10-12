package com.keevo.reporting.export.adapter.out.render.pdf;

import java.util.Arrays;
import java.util.Optional;

/**
 * The PDF documents (Story v1s-18-3, FR95). A template is selected by the single dataset key of
 * the request, so the request, job, audit and gating contracts of v1s-18-1 stay unchanged.
 */
public enum PdfTemplate {

    END_OF_DAY("day_closures", "Fin de journée", "end-of-day", true),
    INVENTORY("inventory_counts", "Rapport d'inventaire", "inventory", false),
    STOCK("stock_levels", "État de stock", "stock", false),
    SALES("sales", "Liste des ventes", "sales", false);

    private final String datasetKey;
    private final String label;
    private final String templateName;
    private final boolean limitedByClosures;

    PdfTemplate(String datasetKey, String label, String templateName, boolean limitedByClosures) {
        this.datasetKey = datasetKey;
        this.label = label;
        this.templateName = templateName;
        this.limitedByClosures = limitedByClosures;
    }

    /** Dataset whose rows feed the document (request key). */
    public String datasetKey() { return datasetKey; }

    /** French document title, listed in the dataset catalogue. */
    public String label() { return label; }

    /** Classpath template {@code pdf/templates/<name>.html}. */
    public String templateName() { return templateName; }

    /** The limit counts closures ({@code pdf-max-closures}) instead of data rows. */
    public boolean limitedByClosures() { return limitedByClosures; }

    public static Optional<PdfTemplate> forDataset(String datasetKey) {
        return Arrays.stream(values()).filter(t -> t.datasetKey.equals(datasetKey)).findFirst();
    }
}
