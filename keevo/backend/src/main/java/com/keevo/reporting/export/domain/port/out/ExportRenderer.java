package com.keevo.reporting.export.domain.port.out;

import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.shared.application.port.export.ExportColumn;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * ExportRenderer — Strategy writing export rows in one file format (Story v1s-18-1).
 * Implementations are collected by format in {@code ExportRendererRegistry}.
 *
 * <p>Renderers stream: each {@link RenderSession#row} is written as it arrives, nothing is
 * buffered beyond a bounded window. The session never closes the target stream.
 */
public interface ExportRenderer {

    ExportFormat format();

    /** HTTP content type of the produced file for {@code datasetCount} datasets. */
    String contentType(int datasetCount);

    /** File extension (without dot) for {@code datasetCount} datasets. */
    String extension(int datasetCount);

    /** Open a rendering session writing to {@code out}. */
    RenderSession open(OutputStream out, int datasetCount) throws IOException;

    /** One file being written: {@code (beginDataset row* endDataset)+ close}. */
    interface RenderSession extends AutoCloseable {

        void beginDataset(String key, String label, List<ExportColumn> columns) throws IOException;

        /** One row, values in column order. */
        void row(List<Object> values) throws IOException;

        void endDataset() throws IOException;

        /** Finish the file and flush it to the target stream (which stays open). */
        @Override
        void close() throws IOException;

        /** Release resources without finishing the file (error path). Never throws. */
        default void abort() {
        }
    }
}
