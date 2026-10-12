package com.keevo.reporting.export.domain.port.out;

import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DocumentExportRenderer — an {@link ExportRenderer} producing a laid-out document (PDF, Story
 * v1s-18-3) instead of a row stream. The document is chosen by the requested dataset(s) and reads
 * the providers itself, within a bounded number of rows.
 *
 * <p>The export service calls, in order and before any rate-limit slot is used:
 * {@link #validate}, then {@link #checkSize} with the providers' counted rows. The streaming
 * writer then delegates to {@link #render} instead of opening a {@link RenderSession}.
 */
public interface DocumentExportRenderer extends ExportRenderer {

    /**
     * Refuse datasets this renderer has no document for.
     *
     * @throws com.keevo.shared.domain.exception.DomainException EXPORT_FORMAT_UNSUPPORTED (400)
     */
    void validate(List<ExportDatasetProvider> providers);

    /**
     * Refuse a document that would exceed its size limit.
     *
     * @param countedRows rows matching {@code filter}, summed over {@code providers}
     * @throws com.keevo.shared.domain.exception.DomainException EXPORT_PDF_TOO_LARGE (422)
     */
    void checkSize(List<ExportDatasetProvider> providers, ExportFilter filter, long countedRows);

    /**
     * Write the document to {@code out} (left open), counting every data row read in
     * {@code progress}.
     *
     * @return data rows rendered
     */
    long render(OutputStream out, List<ExportDatasetProvider> providers, ExportFilter filter,
                AtomicLong progress) throws IOException;

    /** French label of the document produced for one dataset, if any (dataset catalogue). */
    Optional<String> templateLabel(String datasetKey);

    /** Documents are not row streams: {@link #render} replaces sessions. */
    @Override
    default RenderSession open(OutputStream out, int datasetCount) {
        throw new UnsupportedOperationException("Document renderers render whole documents");
    }
}
